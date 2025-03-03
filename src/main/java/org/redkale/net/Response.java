/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Level;
import org.redkale.util.*;

/**
 * 协议响应对象
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @param <C> Context的子类型
 * @param <R> Request的子类型
 */
@SuppressWarnings("unchecked")
public abstract class Response<C extends Context, R extends Request<C>> {

    protected final C context;

    protected Supplier<Response> responseSupplier; //虚拟构建的Response可能不存在responseSupplier

    protected Consumer<Response> responseConsumer; //虚拟构建的Response可能不存在responseConsumer

    protected final ExecutorService workExecutor;

    protected final ThreadHashExecutor workHashExecutor;

    protected final R request;

    protected final WorkThread thread;

    protected AsyncConnection channel;

    private volatile boolean inited = true;

    protected boolean inNonBlocking = true;

    protected Object output; //输出的结果对象

    protected BiConsumer<R, Response<C, R>> recycleListener;

    protected Filter<C, R, ? extends Response<C, R>> filter;

    protected Servlet<C, R, ? extends Response<C, R>> servlet;

    private final ByteBuffer writeBuffer;

    protected final CompletionHandler finishBytesIOThreadHandler = new CompletionHandler<Integer, Void>() {

        @Override
        public void completed(Integer result, Void attachment) {
            completeInIOThread();
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            completeInIOThread(true);
        }

    };

    protected final CompletionHandler finishBufferIOThreadHandler = new CompletionHandler<Integer, ByteBuffer>() {

        @Override
        public void completed(Integer result, ByteBuffer attachment) {
            if (attachment != writeBuffer) {
                channel.offerWriteBuffer(attachment);
            } else {
                attachment.clear();
            }
            completeInIOThread();
        }

        @Override
        public void failed(Throwable exc, ByteBuffer attachment) {
            if (attachment != writeBuffer) {
                channel.offerWriteBuffer(attachment);
            } else {
                attachment.clear();
            }
            completeInIOThread(true);
        }

    };

    private final CompletionHandler finishBuffersIOThreadHandler = new CompletionHandler<Integer, ByteBuffer[]>() {

        @Override
        public void completed(final Integer result, final ByteBuffer[] attachments) {
            if (attachments != null) {
                for (ByteBuffer attachment : attachments) {
                    channel.offerWriteBuffer(attachment);
                }
            }
            completeInIOThread();
        }

        @Override
        public void failed(Throwable exc, final ByteBuffer[] attachments) {
            if (attachments != null) {
                for (ByteBuffer attachment : attachments) {
                    channel.offerWriteBuffer(attachment);
                }
            }
            completeInIOThread(true);
        }

    };

    protected Response(C context, final R request) {
        this.context = context;
        this.request = request;
        this.thread = WorkThread.currWorkThread();
        this.writeBuffer = context != null ? ByteBuffer.allocateDirect(context.getBufferCapacity()) : null;
        this.workExecutor = context == null || context.workExecutor == null ? ForkJoinPool.commonPool() : context.workExecutor;
        this.workHashExecutor = context == null ? null : context.workHashExecutor;
    }

    protected AsyncConnection removeChannel() {
        AsyncConnection ch = this.channel;
        this.channel = null;
        this.request.channel = null;
        return ch;
    }

    protected void prepare() {
        inited = true;
        inNonBlocking = true;
        request.prepare();
    }

    protected boolean recycle() {
        if (!inited) {
            return false;
        }
        this.output = null;
        this.filter = null;
        this.servlet = null;
        boolean noPipeline = request.pipelineIndex == 0 || request.pipelineCompleted;
        request.recycle();
        if (channel != null) {
            if (noPipeline) {
                channel.dispose();
            }
            channel = null;
        }
        this.responseSupplier = null;
        this.responseConsumer = null;
        this.inited = false;
        return true;
    }

    protected ExecutorService getWorkExecutor() {
        return workExecutor;
    }

    protected ThreadHashExecutor getWorkHashExecutor() {
        return workHashExecutor;
    }

    protected void updateNonBlocking(boolean nonBlocking) {
        this.inNonBlocking = nonBlocking;
    }

    protected boolean inNonBlocking() {
        return inNonBlocking;
    }

    protected void refuseAlive() {
        this.request.keepAlive = false;
    }

    protected void init(AsyncConnection channel) {
        this.channel = channel;
        this.request.channel = channel;
        this.request.createTime = System.currentTimeMillis();
    }

    protected void setFilter(Filter<C, R, Response<C, R>> filter) {
        this.filter = filter;
    }

    protected void thenEvent(Servlet servlet) {
        this.servlet = servlet;
    }

    @SuppressWarnings("unchecked")
    public void nextEvent() throws IOException {
        if (this.filter != null) {
            Filter runner = this.filter;
            this.filter = this.filter._next;
            if (inNonBlocking) {
                if (runner.isNonBlocking()) {
                    runner.doFilter(request, this);
                } else {
                    inNonBlocking = false;
                    workExecutor.execute(() -> {
                        try {
                            runner.doFilter(request, Response.this);
                        } catch (Throwable t) {
                            context.getLogger().log(Level.WARNING, "Filter occur exception. request = " + request, t);
                            finishError(t);
                        }
                    });
                }
            } else {
                runner.doFilter(request, this);
            }
            return;
        }
        if (this.servlet != null) {
            Servlet s = this.servlet;
            this.servlet = null;
            if (inNonBlocking) {
                if (s.isNonBlocking()) {
                    s.execute(request, this);
                } else {
                    inNonBlocking = false;
                    workExecutor.execute(() -> {
                        try {
                            s.execute(request, Response.this);
                        } catch (Throwable t) {
                            context.getLogger().log(Level.WARNING, "Servlet occur exception. request = " + request, t);
                            finishError(t);
                        }
                    });
                }
            } else {
                s.execute(request, this);
            }
        }
    }

    public void recycleListener(BiConsumer<R, Response<C, R>> recycleListener) {
        this.recycleListener = recycleListener;
    }

    public Object getOutput() {
        return output;
    }

    /**
     * 是否已关闭
     *
     * @return boolean
     */
    public boolean isClosed() {
        return !this.inited;
    }

    private void completeInIOThread() {
        this.completeInIOThread(false);
    }

    //被重载后kill不一定为true
    protected void finishError(Throwable t) {
        error(t);
    }

    //kill=true
    protected void error(Throwable t) {
        completeInIOThread(true);
    }

    private void completeInIOThread(boolean kill) {
        if (!this.inited) {
            return; //避免重复关闭
        }        //System.println("耗时: " + (System.currentTimeMillis() - request.createtime));
        if (kill) {
            refuseAlive();
        }
        if (this.recycleListener != null) {
            try {
                this.recycleListener.accept(request, this);
            } catch (Exception e) {
                context.logger.log(Level.WARNING, "Response.recycleListener error, request = " + request, e);
            }
            this.recycleListener = null;
        }
        if (request.keepAlive && (request.pipelineIndex == 0 || request.pipelineCompleted)) {
            AsyncConnection conn = removeChannel();
            if (conn != null && conn.protocolCodec != null) {
                this.responseConsumer.accept(this);
                conn.readInIOThread(conn.protocolCodec);
            } else {
                Supplier<Response> poolSupplier = this.responseSupplier;
                Consumer<Response> poolConsumer = this.responseConsumer;
                this.recycle();
                new ProtocolCodec(context, poolSupplier, poolConsumer, conn).response(this).run(null);
            }
        } else {
            this.responseConsumer.accept(this);
        }
    }

    public final void finish(final byte[] bs) {
        finish(false, bs, 0, bs.length);
    }

    public final void finish(final byte[] bs, int offset, int length) {
        finish(false, bs, offset, length);
    }

    public final void finish(final ByteTuple array) {
        finish(false, array.content(), array.offset(), array.length());
    }

    public final void finish(boolean kill, final byte[] bs) {
        finish(kill, bs, 0, bs.length);
    }

    public final void finish(boolean kill, final ByteTuple array) {
        finish(kill, array.content(), array.offset(), array.length());
    }

    public void finish(boolean kill, final byte[] bs, int offset, int length) {
        if (kill) {
            refuseAlive();
        }
        if (request.pipelineIndex > 0) {
            boolean allCompleted = this.channel.appendPipeline(request.pipelineIndex, request.pipelineCount, bs, offset, length);
            if (allCompleted) {
                request.pipelineCompleted = true;
                this.channel.writePipeline(this.finishBytesIOThreadHandler);
            } else {
                removeChannel();
                this.responseConsumer.accept(this);
            }
        } else if (this.channel.hasPipelineData()) {
            this.channel.appendPipeline(request.pipelineIndex, request.pipelineCount, bs, offset, length);
            this.channel.writePipeline(this.finishBytesIOThreadHandler);
        } else {
            ByteBuffer buffer = this.writeBuffer;
            if (buffer != null && buffer.capacity() >= length) {
                buffer.clear();
                buffer.put(bs, offset, length);
                buffer.flip();
                this.channel.write(buffer, buffer, finishBufferIOThreadHandler);
            } else {
                this.channel.write(bs, offset, length, finishBytesIOThreadHandler);
            }
        }
    }

    public <A> void finish(boolean kill, final byte[] bs1, int offset1, int length1, final byte[] bs2, int offset2, int length2, Consumer<A> callback, A attachment) {
        if (kill) {
            refuseAlive();
        }
        if (request.pipelineIndex > 0) {
            boolean allCompleted = this.channel.appendPipeline(request.pipelineIndex, request.pipelineCount, bs1, offset1, length1, bs2, offset2, length2);
            if (allCompleted) {
                request.pipelineCompleted = true;
                this.channel.writePipeline(this.finishBytesIOThreadHandler);
            } else {
                removeChannel();
                this.responseConsumer.accept(this);
            }
        } else if (this.channel.hasPipelineData()) {
            this.channel.appendPipeline(request.pipelineIndex, request.pipelineCount, bs1, offset1, length1, bs2, offset2, length2);
            this.channel.writePipeline(this.finishBytesIOThreadHandler);
        } else {
            this.channel.write(bs1, offset1, length1, bs2, offset2, length2, callback, attachment, finishBytesIOThreadHandler);
        }
    }

    protected void finishBuffers(boolean kill, ByteBuffer... buffers) {
        if (kill) {
            refuseAlive();
        }
        if (request.pipelineIndex > 0) {
            ByteArray array = new ByteArray();
            for (ByteBuffer buffer : buffers) {
                array.put(buffer);
            }
            boolean allCompleted = this.channel.appendPipeline(request.pipelineIndex, request.pipelineCount, array);
            if (allCompleted) {
                request.pipelineCompleted = true;
                this.channel.writePipeline(buffers, this.finishBuffersIOThreadHandler);
            } else {
                AsyncConnection conn = removeChannel();
                if (conn != null) {
                    conn.offerWriteBuffers(buffers);
                }
                this.responseConsumer.accept(this);
            }
        } else if (this.channel.hasPipelineData()) {
            //先将pipeline数据写入完再写入buffers
            this.channel.writePipeline(null, new CompletionHandler<Integer, Void>() {

                @Override
                public void completed(Integer result, Void attachment) {
                    channel.write(buffers, buffers, finishBuffersIOThreadHandler);
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    finishBuffersIOThreadHandler.failed(exc, buffers);
                }
            });
        } else {
            this.channel.write(buffers, buffers, finishBuffersIOThreadHandler);
        }
    }

    protected final void finishBuffer(ByteBuffer buffer) {
        finishBuffers(false, buffer);
    }

    protected final void finishBuffers(ByteBuffer... buffers) {
        finishBuffers(false, buffers);
    }

    protected void finishBuffer(boolean kill, ByteBuffer buffer) {
        finishBuffers(kill, buffer);
    }

    protected <A> void send(final ByteTuple array, final CompletionHandler<Integer, Void> handler) {
        ByteBuffer buffer = this.writeBuffer;
        if (buffer != null && buffer.capacity() >= array.length()) {
            buffer.clear();
            buffer.put(array.content(), array.offset(), array.length());
            buffer.flip();
            this.channel.write(buffer, buffer, new CompletionHandler<Integer, ByteBuffer>() {

                @Override
                public void completed(Integer result, ByteBuffer attachment) {
                    attachment.clear();
                    handler.completed(result, null);
                }

                @Override
                public void failed(Throwable exc, ByteBuffer attachment) {
                    attachment.clear();
                    handler.failed(exc, null);
                }

            });
        } else {
            this.channel.write(array, handler);
        }
    }

    protected <A> void send(final ByteBuffer buffer, final A attachment, final CompletionHandler<Integer, A> handler) {
        this.channel.write(buffer, attachment, new CompletionHandler<Integer, A>() {

            @Override
            public void completed(Integer result, A attachment) {
                if (buffer != writeBuffer) {
                    channel.offerWriteBuffer(buffer);
                } else {
                    buffer.clear();
                }
                if (handler != null) {
                    handler.completed(result, attachment);
                }
            }

            @Override
            public void failed(Throwable exc, A attachment) {
                if (buffer != writeBuffer) {
                    channel.offerWriteBuffer(buffer);
                } else {
                    buffer.clear();
                }
                if (handler != null) {
                    handler.failed(exc, attachment);
                }
            }

        });
    }

    protected <A> void send(final ByteBuffer[] buffers, A attachment, final CompletionHandler<Integer, A> handler) {
        this.channel.write(buffers, attachment, new CompletionHandler<Integer, A>() {

            @Override
            public void completed(Integer result, A attachment) {
                channel.offerWriteBuffers(buffers);
                if (handler != null) {
                    handler.completed(result, attachment);
                }
            }

            @Override
            public void failed(Throwable exc, A attachment) {
                for (ByteBuffer buffer : buffers) {
                    channel.offerWriteBuffer(buffer);
                }
                if (handler != null) {
                    handler.failed(exc, attachment);
                }
            }

        });
    }

    public C getContext() {
        return context;
    }
}
