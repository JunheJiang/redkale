/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.function.*;
import java.util.logging.Level;

/**
 * 一个AsyncConnection绑定一个ProtocolCodec实例, 只会在读IOThread中运行
 *
 * @author zhangjx
 */
class ProtocolCodec implements CompletionHandler<Integer, ByteBuffer> {

    private final Context context;

    private final Supplier<Response> responseSupplier;

    private final Consumer<Response> responseConsumer;

    private final ReadCompletionHandler readHandler = new ReadCompletionHandler();

    private AsyncConnection channel;

    private Response resp;

    public ProtocolCodec(Context context, Supplier<Response> responseSupplier,
        Consumer<Response> responseConsumer, AsyncConnection channel) {
        this.context = context;
        this.channel = channel;
        this.responseSupplier = responseSupplier;
        this.responseConsumer = responseConsumer;
    }

    public void prepare() {
    }

    protected boolean recycle() {
        this.channel = null;
        this.resp = null;
        return true;
    }

    public ProtocolCodec response(Response resp) {
        this.resp = resp;
        return this;
    }

    private Response createResponse() {
        Response response = resp;
        if (response == null) {
            response = responseSupplier.get();
        } else {
            response.prepare();
        }
        response.responseSupplier = responseSupplier;
        response.responseConsumer = responseConsumer;
        return response;
    }

    @Override
    public void completed(Integer count, ByteBuffer buffer) {
        if (count < 1) {
            channel.offerReadBuffer(buffer);
            channel.dispose(); // response.init(channel); 在调用之前异常
            return;
        }
//        {  //测试
//            buffer.flip();
//            byte[] bs = new byte[buffer.remaining()];
//            buffer.get(bs);
//            System.println(new String(bs));
//        }
        buffer.flip();
        final Response response = createResponse();
        try {
            decode(buffer, response, 0, null);
        } catch (Throwable t) {  //此处不可  context.offerBuffer(buffer); 以免prepare.prepare内部异常导致重复 offerBuffer
            context.logger.log(Level.WARNING, "prepare servlet abort, force to close channel ", t);
            response.error(t);
        }
    }

    @Override
    public void failed(Throwable exc, ByteBuffer buffer) {
        channel.offerReadBuffer(buffer);
        channel.dispose();// response.init(channel); 在调用之前异常
        if (exc != null && context.logger.isLoggable(Level.FINEST)
            && !(exc instanceof SocketException && "Connection reset".equals(exc.getMessage()))) {
            context.logger.log(Level.FINEST, "Servlet Handler read channel erroneous, force to close channel ", exc);
        }
    }

    public void start(final ByteBuffer data) {
        if (data != null) { //pipeline模式或UDP连接创建AsyncConnection时已经获取到ByteBuffer数据了
            final Response response = createResponse();
            try {
                decode(data, response, 0, null);
            } catch (Throwable t) {
                context.logger.log(Level.WARNING, "prepare servlet abort, force to close channel ", t);
                response.error(t);
            }
            return;
        }
        try {
            channel.startRead(this);
        } catch (Exception te) {
            channel.dispose();// response.init(channel); 在调用之前异常
            if (context.logger.isLoggable(Level.FINEST)) {
                context.logger.log(Level.FINEST, "Servlet start read channel erroneous, force to close channel ", te);
            }
        }
    }

    public void run(final ByteBuffer data) {
        if (data != null) { //pipeline模式或UDP连接创建AsyncConnection时已经获取到ByteBuffer数据了
            final Response response = createResponse();
            try {
                decode(data, response, 0, null);
            } catch (Throwable t) {
                context.logger.log(Level.WARNING, "prepare servlet abort, force to close channel ", t);
                response.error(t);
            }
            return;
        }
        try {
            channel.read(this);
        } catch (Exception te) {
            channel.dispose();// response.init(channel); 在调用之前异常
            if (context.logger.isLoggable(Level.FINEST)) {
                context.logger.log(Level.FINEST, "Servlet run read channel erroneous, force to close channel ", te);
            }
        }
    }

    protected void decode(final ByteBuffer buffer, final Response response, final int pipelineIndex, final Request lastReq) {
        response.init(channel);
        final Request request = response.request;
        final int rs = request.readHeader(buffer, lastReq);
        if (rs < 0) {  //表示数据格式不正确
            final DispatcherServlet preparer = context.dispatcher;
            preparer.incrExecuteCounter();
            channel.offerReadBuffer(buffer);
            if (rs != Integer.MIN_VALUE) {
                preparer.incrIllegalRequestCounter();
            }
            response.error(null);
            if (context.logger.isLoggable(Level.FINEST)) {
                context.logger.log(Level.FINEST, "request.readHeader erroneous (" + rs + "), force to close channel ");
            }
        } else if (rs == 0) {
            context.dispatcher.incrExecuteCounter();
            int pindex = pipelineIndex;
            boolean pipeline = false;
            Request hreq = lastReq;
            if (buffer.hasRemaining()) {
                pipeline = true;
                if (pindex == 0) {
                    pindex++;
                }
                if (request.getRequestid() == null) { //存在requestid则无视pipeline模式
                    request.pipeline(pindex, pindex + 1);
                }
                if (hreq == null) {
                    hreq = request.copyHeader();
                }
            } else {
                if (request.getRequestid() == null) { //存在requestid则无视pipeline模式
                    request.pipeline(pindex, pindex);
                }
                channel.setReadBuffer(buffer.clear());
            }
            context.executeDispatch(request, response);
            if (pipeline) {
                final Response pipelineResponse = createResponse();
                try {
                    decode(buffer, pipelineResponse, pindex + 1, hreq);
                } catch (Throwable t) {  //此处不可  offerBuffer(buffer); 以免prepare.prepare内部异常导致重复 offerBuffer
                    context.logger.log(Level.WARNING, "prepare pipeline servlet abort, force to close channel ", t);
                    pipelineResponse.error(t);
                }
            }
        } else {
            channel.setReadBuffer(buffer);
            channel.read(readHandler.prepare(request, response, pipelineIndex, lastReq));
        }
    }

    private class ReadCompletionHandler implements CompletionHandler<Integer, ByteBuffer> {

        private Request request;

        private Response response;

        private int pipelineIndex;

        private Request lastReq;

        public ReadCompletionHandler prepare(Request request, Response response, int pipelineIndex, Request lastReq) {
            this.request = request;
            this.response = response;
            this.pipelineIndex = pipelineIndex;
            this.lastReq = lastReq;
            return this;
        }

        @Override
        public void completed(Integer count, ByteBuffer attachment) {
            if (count < 1) {
                channel.offerReadBuffer(attachment);
                channel.dispose();
                return;
            }
            attachment.flip();
            decode(attachment, response, pipelineIndex, lastReq);
        }

        @Override
        public void failed(Throwable exc, ByteBuffer attachment) {
            context.dispatcher.incrIllegalRequestCounter();
            channel.offerReadBuffer(attachment);
            response.error(exc);
            if (exc != null) {
                request.context.logger.log(Level.FINER, "Servlet continue read channel erroneous, force to close channel ", exc);
            }
        }
    }
}
