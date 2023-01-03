/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.*;
import java.util.logging.Level;
import org.redkale.boot.Application;
import org.redkale.util.*;

/**
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.1.0
 */
class AsyncNioTcpProtocolServer extends ProtocolServer {

    private ServerSocketChannel serverChannel;

    private Selector selector;

    private AsyncIOGroup ioGroup;

    private Thread acceptThread;

    private boolean closed;

    private Supplier<Response> responseSupplier;

    private Consumer<Response> responseConsumer;

    public AsyncNioTcpProtocolServer(Context context) {
        super(context);
    }

    @Override
    public void open(AnyValue config) throws IOException {
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);
        this.selector = Selector.open();
        final Set<SocketOption<?>> options = this.serverChannel.supportedOptions();
        if (options.contains(StandardSocketOptions.TCP_NODELAY)) {
            this.serverChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
        if (options.contains(StandardSocketOptions.SO_KEEPALIVE)) {
            this.serverChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        }
        if (options.contains(StandardSocketOptions.SO_REUSEADDR)) {
            this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        }
        if (options.contains(StandardSocketOptions.SO_RCVBUF)) {
            this.serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, 16 * 1024);
        }
        if (options.contains(StandardSocketOptions.SO_SNDBUF)) {
            this.serverChannel.setOption(StandardSocketOptions.SO_SNDBUF, 16 * 1024);
        }
    }

    @Override
    public void bind(SocketAddress local, int backlog) throws IOException {
        this.serverChannel.bind(local, backlog);
    }

    @Override
    public <T> Set<SocketOption<?>> supportedOptions() {
        return this.serverChannel.supportedOptions();
    }

    @Override
    public <T> void setOption(SocketOption<T> name, T value) throws IOException {
        this.serverChannel.setOption(name, value);
    }

    @Override
    public void accept(Application application, Server server) throws IOException {
        this.serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);

        LongAdder createBufferCounter = new LongAdder();
        LongAdder cycleBufferCounter = new LongAdder();
        LongAdder createResponseCounter = new LongAdder();
        LongAdder cycleResponseCounter = new LongAdder();

        ObjectPool<ByteBuffer> bufferPool = server.createBufferPool(createBufferCounter, cycleBufferCounter, server.bufferPoolSize);
        ObjectPool<Response> safeResponsePool = server.createResponsePool(createResponseCounter, cycleResponseCounter, server.responsePoolSize);
        final int respPoolMax = server.getResponsePoolSize();
        ThreadLocal<ObjectPool<Response>> localResponsePool = ThreadLocal.withInitial(() -> {
            if (!(Thread.currentThread() instanceof WorkThread)) {
                return null;
            }
            return ObjectPool.createUnsafePool(safeResponsePool, safeResponsePool.getCreatCounter(),
                safeResponsePool.getCycleCounter(), respPoolMax, safeResponsePool.getCreator(), safeResponsePool.getPrepare(), safeResponsePool.getRecycler());
        });
        this.responseSupplier = () -> {
            ObjectPool<Response> pool = localResponsePool.get();
            return pool == null ? safeResponsePool.get() : pool.get();
        };
        this.responseConsumer = (v) -> {
            WorkThread thread = v.channel != null ? v.channel.getWriteIOThread() : v.thread;
            if (thread != null && !thread.inCurrThread()) {
                thread.execute(() -> {
                    ObjectPool<Response> pool = localResponsePool.get();
                    (pool == null ? safeResponsePool : pool).accept(v);
                });
                return;
            }
            ObjectPool<Response> pool = localResponsePool.get();
            (pool == null ? safeResponsePool : pool).accept(v);
        };
        final String threadPrefixName = server.name == null || server.name.isEmpty() ? "Redkale-IOServletThread" : ("Redkale-" + server.name.replace("Server-", "") + "-IOServletThread");
        this.ioGroup = new AsyncIOGroup(false, threadPrefixName, null, server.bufferCapacity, bufferPool);
        this.ioGroup.start();

        this.acceptThread = new Thread() {
            {
                setName(threadPrefixName.replace("ServletThread", "AcceptThread"));
            }

            @Override
            public void run() {
                while (!closed) {
                    try {
                        int count = selector.select();
                        if (count == 0) {
                            continue;
                        }
                        Set<SelectionKey> keys = selector.selectedKeys();
                        Iterator<SelectionKey> it = keys.iterator();
                        while (it.hasNext()) {
                            SelectionKey key = it.next();
                            it.remove();
                            if (key.isAcceptable()) {
                                accept(key);
                            }
                        }
                    } catch (Throwable t) {
                        server.logger.log(Level.SEVERE, "server accept error", t);
                    }
                }
            }
        };
        this.acceptThread.start();
    }

    private void accept(SelectionKey key) throws IOException {
        SocketChannel channel = this.serverChannel.accept();
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.setOption(StandardSocketOptions.SO_RCVBUF, 16 * 1024);
        channel.setOption(StandardSocketOptions.SO_SNDBUF, 16 * 1024);
        AsyncIOThread readThread = ioGroup.nextIOThread();
        LongAdder connCreateCounter = ioGroup.connCreateCounter;
        if (connCreateCounter != null) {
            connCreateCounter.increment();
        }
        LongAdder connLivingCounter = ioGroup.connLivingCounter;
        if (connLivingCounter != null) {
            connLivingCounter.increment();
        }
        AsyncNioTcpConnection conn = new AsyncNioTcpConnection(false, ioGroup, readThread, ioGroup.connectThread(), channel, context.getSSLBuilder(), context.getSSLContext(), null, connLivingCounter, ioGroup.connClosedCounter);
        ProtocolCodec codec = new ProtocolCodec(context, responseSupplier, responseConsumer, conn);
        conn.protocolCodec = codec;
        if (conn.sslEngine == null) {
            codec.start(null);
        } else {
            conn.startHandshake(t -> {
                if (t == null) {
                    codec.start(null);
                } else if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                } else {
                    throw new RuntimeException(t);
                }
            });
        }
    }

    @Override
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.selector.wakeup();
        this.ioGroup.close();
        this.serverChannel.close();
        this.selector.close();
    }

    @Override
    public AsyncGroup getAsyncGroup() {
        return ioGroup;
    }

    @Override
    public long getCreateConnectionCount() {
        return ioGroup.connCreateCounter == null ? -1 : ioGroup.connCreateCounter.longValue();
    }

    @Override
    public long getClosedConnectionCount() {
        return ioGroup.connClosedCounter == null ? -1 : ioGroup.connClosedCounter.longValue();
    }

    @Override
    public long getLivingConnectionCount() {
        return ioGroup.connLivingCounter == null ? -1 : ioGroup.connLivingCounter.longValue();
    }
}
