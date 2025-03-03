/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.client;

import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.Logger;
import org.redkale.net.*;
import org.redkale.util.*;

/**
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @since 2.3.0
 *
 * @param <C> 连接对象
 * @param <R> 请求对象
 * @param <P> 响应对象
 */
public abstract class Client<C extends ClientConnection<R, P>, R extends ClientRequest, P> implements Resourcable {

    public static final int DEFAULT_MAX_PIPELINES = 128;

    protected final Logger logger = Logger.getLogger(this.getClass().getSimpleName());

    protected final String name;

    protected final AsyncGroup group; //连接构造器

    protected final boolean tcp; //是否TCP协议

    protected final ClientAddress address;  //连接的地址

    protected final ScheduledThreadPoolExecutor timeoutScheduler;

    //结合ClientRequest.isCompleted()使用
    //使用场景：批量request提交时，后面的request需响应上一个request返回值来构建
    //例如： MySQL批量提交PrepareSQL场景
    protected final LongAdder reqWritedCounter = new LongAdder();

    protected final LongAdder respDoneCounter = new LongAdder();

    protected final AtomicLong connIndexSeq = new AtomicLong();

    private final AtomicInteger connSeqno = new AtomicInteger();

    private final AtomicBoolean closed = new AtomicBoolean();

    protected ScheduledFuture timeoutFuture;

    protected ClientConnection<R, P>[] connArray;  //连接池

    protected LongAdder[] connRespWaitings;  //连接当前处理数

    protected AtomicBoolean[] connOpenStates; //conns的标记组，当conn不存在或closed状态，标记为false

    protected final Queue<CompletableFuture<C>>[] connAcquireWaitings;  //连接等待池

    protected int connLimit = Utility.cpus();  //最大连接数

    protected int maxPipelines = DEFAULT_MAX_PIPELINES; //单个连接最大并行处理数

    protected int readTimeoutSeconds;

    protected int writeTimeoutSeconds;

    protected String connectionContextName;

    //------------------ 可选项 ------------------
    //PING心跳的请求数据，为null且pingInterval<1表示不需要定时ping
    protected Supplier<R> pingRequestSupplier;

    //关闭请求的数据， 为null表示直接关闭
    protected Supplier<R> closeRequestSupplier;

    //创建连接后进行的登录鉴权操作
    protected Function<C, CompletableFuture<C>> authenticate;

    protected Client(String name, AsyncGroup group, ClientAddress address) {
        this(name, group, true, address, Utility.cpus(), DEFAULT_MAX_PIPELINES, null, null, null);
    }

    protected Client(String name, AsyncGroup group, boolean tcp, ClientAddress address) {
        this(name, group, tcp, address, Utility.cpus(), DEFAULT_MAX_PIPELINES, null, null, null);
    }

    protected Client(String name, AsyncGroup group, boolean tcp, ClientAddress address, int maxConns) {
        this(name, group, tcp, address, maxConns, DEFAULT_MAX_PIPELINES, null, null, null);
    }

    protected Client(String name, AsyncGroup group, boolean tcp, ClientAddress address, int maxConns, int maxPipelines) {
        this(name, group, tcp, address, maxConns, maxPipelines, null, null, null);
    }

    protected Client(String name, AsyncGroup group, boolean tcp, ClientAddress address, int maxConns,
        Function<C, CompletableFuture<C>> authenticate) {
        this(name, group, tcp, address, maxConns, DEFAULT_MAX_PIPELINES, null, null, authenticate);
    }

    protected Client(String name, AsyncGroup group, boolean tcp, ClientAddress address, int maxConns,
        Supplier<R> closeRequestSupplier, Function<C, CompletableFuture<C>> authenticate) {
        this(name, group, tcp, address, maxConns, DEFAULT_MAX_PIPELINES, null, closeRequestSupplier, authenticate);
    }

    @SuppressWarnings("OverridableMethodCallInConstructor")
    protected Client(String name, AsyncGroup group, boolean tcp, ClientAddress address, int maxConns,
        int maxPipelines, Supplier<R> pingRequestSupplier, Supplier<R> closeRequestSupplier, Function<C, CompletableFuture<C>> authenticate) {
        if (maxPipelines < 1) {
            throw new IllegalArgumentException("maxPipelines must bigger 0");
        }
        address.checkValid();
        this.name = name;
        this.group = group;
        this.tcp = tcp;
        this.address = address;
        this.connLimit = maxConns;
        this.maxPipelines = maxPipelines;
        this.pingRequestSupplier = pingRequestSupplier;
        this.closeRequestSupplier = closeRequestSupplier;
        this.authenticate = authenticate;
        this.connArray = new ClientConnection[connLimit];
        this.connOpenStates = new AtomicBoolean[connLimit];
        this.connRespWaitings = new LongAdder[connLimit];
        this.connAcquireWaitings = new Queue[connLimit];
        for (int i = 0; i < connOpenStates.length; i++) {
            this.connOpenStates[i] = new AtomicBoolean();
            this.connRespWaitings[i] = new LongAdder();
            this.connAcquireWaitings[i] = Utility.unsafe() != null ? new MpscGrowableArrayQueue<>(16, 1 << 10) : new ConcurrentLinkedDeque();
        }
        //timeoutScheduler 不仅仅给超时用， 还给write用
        this.timeoutScheduler = new ScheduledThreadPoolExecutor(1, (Runnable r) -> {
            final Thread t = new Thread(r, "Redkale-" + Client.this.getClass().getSimpleName() + "-" + resourceName() + "-Timeout-Thread");
            t.setDaemon(true);
            return t;
        });
        if (pingRequestSupplier != null && this.timeoutFuture == null) {
            this.timeoutFuture = this.timeoutScheduler.scheduleAtFixedRate(() -> {
                try {
                    R req = pingRequestSupplier.get();
                    if (req == null) { //可能运行中进行重新赋值
                        timeoutFuture.cancel(true);
                        timeoutFuture = null;
                        return;
                    }
                    long now = System.currentTimeMillis();
                    for (ClientConnection<R, P> conn : this.connArray) {
                        if (conn == null) {
                            continue;
                        }
                        if (now - conn.getLastWriteTime() < 10_000) {
                            continue;
                        }
                        conn.writeChannel(req).thenAccept(p -> handlePingResult((C) conn, p));
                    }
                } catch (Throwable t) {
                }
            }, pingIntervalSeconds(), pingIntervalSeconds(), TimeUnit.SECONDS);
        }
    }

    protected abstract C createClientConnection(final int index, AsyncConnection channel);

    //创建连接后先从服务器拉取数据构建的虚拟请求，返回null表示连上服务器后不读取数据
    protected R createVirtualRequestAfterConnect() {
        return null;
    }

    protected int pingIntervalSeconds() {
        return 30;
    }

    protected void handlePingResult(C conn, P result) {
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            this.timeoutScheduler.shutdownNow();
            for (ClientConnection conn : this.connArray) {
                if (conn == null) {
                    continue;
                }
                final R closeReq = closeRequestSupplier == null ? null : closeRequestSupplier.get();
                if (closeReq == null) {
                    conn.dispose(null);
                } else {
                    try {
                        conn.writeChannel(closeReq).get(1, TimeUnit.SECONDS);
                    } catch (Exception e) {
                    }
                    conn.dispose(null);
                }
            }
            group.close();
        }
    }

    public final CompletableFuture<P> sendAsync(R request) {
        if (request.workThread == null) {
            request.workThread = WorkThread.currWorkThread();
        }
        return connect().thenCompose(conn -> writeChannel(conn, request));
    }

    public final <T> CompletableFuture<T> sendAsync(R request, Function<P, T> respTransfer) {
        if (request.workThread == null) {
            request.workThread = WorkThread.currWorkThread();
        }
        return connect().thenCompose(conn -> writeChannel(conn, request, respTransfer));
    }

    protected CompletableFuture<P> writeChannel(ClientConnection conn, R request) {
        return conn.writeChannel(request);
    }

    protected <T> CompletableFuture<T> writeChannel(ClientConnection conn, R request, Function<P, T> respTransfer) {
        return conn.writeChannel(request, respTransfer);
    }

    protected CompletableFuture<C> connect() {
        final int size = this.connArray.length;
        final int connIndex = (int) Math.abs(connIndexSeq.getAndIncrement()) % size;
        C cc = (C) this.connArray[connIndex];
        if (cc != null && cc.isOpen()) {
            return CompletableFuture.completedFuture(cc);
        }
        final Queue<CompletableFuture<C>> waitQueue = this.connAcquireWaitings[connIndex];
        if (this.connOpenStates[connIndex].compareAndSet(false, true)) {
            CompletableFuture<C> future = address.createClient(tcp, group, readTimeoutSeconds, writeTimeoutSeconds)
                .thenApply(c -> (C) createClientConnection(connIndex, c).setMaxPipelines(maxPipelines));
            R virtualReq = createVirtualRequestAfterConnect();
            if (virtualReq != null) {
                future = future.thenCompose(conn -> conn.writeVirtualRequest(virtualReq).thenApply(v -> conn));
            } else {
                future = future.thenApply(conn -> (C) conn.readChannel());
            }
            if (authenticate != null) {
                future = future.thenCompose(authenticate);
            }
            return future.thenApply(c -> {
                c.setAuthenticated(true);
                this.connArray[connIndex] = c;
                CompletableFuture<C> f;
                while ((f = waitQueue.poll()) != null) {
                    f.complete(c);
                }
                return c;
            }).whenComplete((r, t) -> {
                if (t != null) {
                    this.connOpenStates[connIndex].set(false);
                }
            });
        } else {
            CompletableFuture rs = Utility.orTimeout(new CompletableFuture(), 6, TimeUnit.SECONDS);
            waitQueue.offer(rs);
            return rs;
        }
//        }
//        ClientConnection minRunningConn = null;
//        for (int i = 0; i < size; i++) {
//            final int index = i;
//            final ClientConnection conn = this.connArray[index];
//            if (conn == null || !conn.isOpen()) {
//                if (this.connOpenStates[index].compareAndSet(false, true)) {
//                    CompletableFuture<ClientConnection> future = group.createClient(tcp, address, readTimeoutSeconds, writeTimeoutSeconds)
//                        .thenApply(c -> createClientConnection(index, c).setMaxPipelines(maxPipelines));
//                    return (authenticate == null ? future : authenticate.apply(future)).thenApply(c -> {
//                        c.authenticated = true;
//                        this.connArray[index] = c;
//                        return c;
//                    }).whenComplete((r, t) -> {
//                        if (t != null) this.connOpenStates[index].set(false);
//                    });
//                }
//            } else if (conn.runningCount() < 1) {
//                return CompletableFuture.completedFuture(conn);
//            } else if (minRunningConn == null || minRunningConn.runningCount() > conn.runningCount()) {
//                minRunningConn = conn;
//            }
//        }
//        if (minRunningConn != null) { // && minRunningConn.runningCount() < maxPipelines
//            return CompletableFuture.completedFuture(minRunningConn);
//        }
//        CompletableFuture rs = Utility.orTimeout(new CompletableFuture(), 6, TimeUnit.SECONDS);
//        connAcquireWaitings[connSeqno.getAndIncrement() % this.connLimit].offer(rs);
//        return rs;
    }

    protected long getRespWaitingCount() {
        long s = 0;
        for (LongAdder a : connRespWaitings) {
            s += a.longValue();
        }
        return s;
    }

    protected void incrReqWritedCounter() {
        reqWritedCounter.increment();
    }

    protected void incrRespDoneCounter() {
        respDoneCounter.increment();
    }

    @Override
    public String resourceName() {
        return name;
    }

    public String getName() {
        return name;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getWriteTimeoutSeconds() {
        return writeTimeoutSeconds;
    }

    public void setWriteTimeoutSeconds(int writeTimeoutSeconds) {
        this.writeTimeoutSeconds = writeTimeoutSeconds;
    }

}
