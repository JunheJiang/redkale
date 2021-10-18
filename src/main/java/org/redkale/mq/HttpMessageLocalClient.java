/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.mq;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.redkale.boot.*;
import org.redkale.convert.*;
import org.redkale.convert.json.JsonConvert;
import org.redkale.net.http.*;

/**
 * 没有配置MQ且也没有ClusterAgent的情况下实现的默认HttpMessageClient实例
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.4.0
 */
public class HttpMessageLocalClient extends HttpMessageClient {

    protected final Application application;

    protected final String resourceName;

    protected HttpServer server;

    public HttpMessageLocalClient(Application application, String resourceName) {
        super(null);
        this.application = application;
        this.resourceName = resourceName;
    }

    private HttpServer httpServer() {
        if (this.server == null) {
            NodeHttpServer nodeHttpServer = null;
            List<NodeServer> nodeServers = application.getNodeServers();
            for (NodeServer n : nodeServers) {
                if (n.getClass() == NodeHttpServer.class && Objects.equals(resourceName, ((NodeHttpServer) n).getHttpServer().getName())) {
                    nodeHttpServer = (NodeHttpServer) n;
                    break;
                }
            }
            if (nodeHttpServer == null) {
                for (NodeServer n : nodeServers) {
                    if (n.getClass() == NodeHttpServer.class) {
                        nodeHttpServer = (NodeHttpServer) n;
                        break;
                    }
                }
            }
            this.server = nodeHttpServer.getServer();
        }
        return this.server;
    }

    protected HttpContext context() {
        return httpServer().getContext();
    }

    protected HttpPrepareServlet prepareServlet() {
        return (HttpPrepareServlet) httpServer().getPrepareServlet();
    }

    @Override
    public <T> CompletableFuture<T> sendMessage(HttpSimpleRequest request, Type type) {
        HttpPrepareServlet ps = prepareServlet();
        String topic = generateHttpReqTopic(request, request.getPath());
        HttpServlet servlet = ps.findServletByTopic(topic);
        CompletableFuture future = new CompletableFuture();
        if (servlet == null) {
            future.completeExceptionally(new RuntimeException("404 Not Found " + topic));
            return future;
        }
        HttpRequest req = new HttpMessageLocalRequest(context(), request);
        HttpResponse resp = new HttpMessageLocalResponse(req, future);
        try {
            servlet.execute(req, resp);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public <T> CompletableFuture<T> sendMessage(Serializable userid, HttpSimpleRequest request, Type type) {
        HttpPrepareServlet ps = prepareServlet();
        String topic = generateHttpReqTopic(request, request.getPath());
        HttpServlet servlet = ps.findServletByTopic(topic);
        CompletableFuture future = new CompletableFuture();
        if (servlet == null) {
            future.completeExceptionally(new RuntimeException("404 Not Found " + topic));
            return future;
        }
        HttpRequest req = new HttpMessageLocalRequest(context(), request);
        HttpResponse resp = new HttpMessageLocalResponse(req, future);
        try {
            servlet.execute(req, resp);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public <T> CompletableFuture<T> sendMessage(Serializable userid, String groupid, HttpSimpleRequest request, Type type) {
        HttpPrepareServlet ps = prepareServlet();
        String topic = generateHttpReqTopic(request, request.getPath());
        HttpServlet servlet = ps.findServletByTopic(topic);
        CompletableFuture future = new CompletableFuture();
        if (servlet == null) {
            future.completeExceptionally(new RuntimeException("404 Not Found " + topic));
            return future;
        }
        HttpRequest req = new HttpMessageLocalRequest(context(), request);
        HttpResponse resp = new HttpMessageLocalResponse(req, future);
        try {
            servlet.execute(req, resp);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletableFuture<HttpResult<byte[]>> sendMessage(String topic, Serializable userid, String groupid, HttpSimpleRequest request, AtomicLong counter) {
        HttpPrepareServlet ps = prepareServlet();
        HttpServlet servlet = ps.findServletByTopic(topic);
        if (servlet == null) return CompletableFuture.completedFuture(new HttpResult().status(404));
        HttpRequest req = new HttpMessageLocalRequest(context(), request);
        CompletableFuture future = new CompletableFuture();
        HttpResponse resp = new HttpMessageLocalResponse(req, future);
        try {
            servlet.execute(req, resp);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future.thenApply(rs -> {
            if (rs == null) return new HttpResult();
            if (rs instanceof HttpResult) {
                Object result = ((HttpResult) rs).getResult();
                if (result == null || result instanceof byte[]) return (HttpResult) rs;
                return new HttpResult(JsonConvert.root().convertToBytes(result));
            }
            return new HttpResult(JsonConvert.root().convertToBytes(rs));
        });
    }

    @Override
    public void produceMessage(String topic, Serializable userid, String groupid, HttpSimpleRequest request, AtomicLong counter) {
        HttpPrepareServlet ps = prepareServlet();
        HttpServlet servlet = ps.findServletByTopic(topic);
        if (servlet == null) return;
        HttpRequest req = new HttpMessageLocalRequest(context(), request);
        HttpResponse resp = new HttpMessageLocalResponse(req, null);
        try {
            servlet.execute(req, resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void broadcastMessage(String topic, Serializable userid, String groupid, HttpSimpleRequest request, AtomicLong counter) {
        HttpPrepareServlet ps = prepareServlet();
        HttpRequest req = new HttpMessageLocalRequest(context(), request);
        HttpResponse resp = new HttpMessageLocalResponse(req, null);
        ps.filterServletsByMmcTopic(topic).forEach(s -> {
            try {
                s.execute(req, resp);
            } catch (Exception e) {
                logger.log(Level.SEVERE, request + " execute " + s + " error", e);
            }
        });
    }

    public static class HttpMessageLocalRequest extends HttpRequest {

        public HttpMessageLocalRequest(HttpContext context, HttpSimpleRequest req) {
            super(context, req);
        }
    }

    public static class HttpMessageLocalResponse extends HttpResponse {

        private CompletableFuture future;

        public HttpMessageLocalResponse(HttpRequest req, CompletableFuture future) {
            super(req.getContext(), req, null);
            this.future = future;
        }

        @Override
        public void finish(String obj) {
            if (future == null) return;
            future.complete(obj == null ? "" : obj);
        }

        @Override
        public void finish404() {
            finish(404, null);
        }

        @Override
        public void finish(int status, String msg) {
            if (future == null) return;
            if (status == 0 || status == 200) {
                future.complete(msg == null ? "" : msg);
            } else {
                future.complete(new HttpResult(msg == null ? "" : msg).status(status));
            }
        }

        @Override
        public void finish(final Convert convert, Type valueType, HttpResult result) {
            if (future == null) return;
            if (convert != null) result.convert(convert);
            future.complete(result);
        }

        @Override
        public void finish(boolean kill, final byte[] bs, int offset, int length) {
            if (future == null) return;
            if (offset == 0 && bs.length == length) {
                future.complete(bs);
            } else {
                future.complete(Arrays.copyOfRange(bs, offset, offset + length));
            }
        }

        @Override
        public void finish(boolean kill, final String contentType, final byte[] bs, int offset, int length) {
            if (future == null) return;
            byte[] rs = (offset == 0 && bs.length == length) ? bs : Arrays.copyOfRange(bs, offset, offset + length);
            future.complete(rs);
        }

        @Override
        public void finish(boolean kill, ByteBuffer buffer) {
            if (future == null) return;
            byte[] bs = new byte[buffer.remaining()];
            buffer.get(bs);
            future.complete(bs);
        }

        @Override
        public void finish(boolean kill, ByteBuffer... buffers) {
            if (future == null) return;
            int size = 0;
            for (ByteBuffer buf : buffers) {
                size += buf.remaining();
            }
            byte[] bs = new byte[size];
            int index = 0;
            for (ByteBuffer buf : buffers) {
                int r = buf.remaining();
                buf.get(bs, index, r);
                index += r;
            }
            future.complete(bs);
        }
    }
}
