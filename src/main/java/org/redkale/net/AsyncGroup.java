/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.net.SocketAddress;
import java.util.concurrent.*;
import org.redkale.util.*;

/**
 * Client模式的AsyncConnection连接构造器
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.3.0
 */
public abstract class AsyncGroup {

    public static final int UDP_BUFFER_CAPACITY = Integer.getInteger("redkale.udp.buffer.apacity", 1350);

    public static AsyncGroup create(String threadNameFormat, final ExecutorService workExecutor, final int bufferCapacity, final int bufferPoolSize) {
        return new AsyncIOGroup(true, threadNameFormat, workExecutor, bufferCapacity, bufferPoolSize);
    }

    public static AsyncGroup create(String threadNameFormat, ExecutorService workExecutor, final ByteBufferPool safeBufferPool) {
        return new AsyncIOGroup(true, threadNameFormat, workExecutor, safeBufferPool);
    }

    public static AsyncGroup create(boolean clientMode, String threadNameFormat, ExecutorService workExecutor, final int bufferCapacity, final int bufferPoolSize) {
        return new AsyncIOGroup(clientMode, threadNameFormat, workExecutor, bufferCapacity, bufferPoolSize);
    }

    public static AsyncGroup create(boolean clientMode, String threadNameFormat, ExecutorService workExecutor, ByteBufferPool safeBufferPool) {
        return new AsyncIOGroup(clientMode, threadNameFormat, workExecutor, safeBufferPool);
    }

    public static AsyncGroup create(String threadNameFormat, int threads, ExecutorService workExecutor, final int bufferCapacity, final int bufferPoolSize) {
        return new AsyncIOGroup(true, threadNameFormat, threads, workExecutor, bufferCapacity, bufferPoolSize);
    }

    public static AsyncGroup create(String threadNameFormat, int threads, ExecutorService workExecutor, final ByteBufferPool safeBufferPool) {
        return new AsyncIOGroup(true, threadNameFormat, threads, workExecutor, safeBufferPool);
    }

    public static AsyncGroup create(boolean clientMode, String threadNameFormat, int threads, ExecutorService workExecutor, final int bufferCapacity, final int bufferPoolSize) {
        return new AsyncIOGroup(clientMode, threadNameFormat, threads, workExecutor, bufferCapacity, bufferPoolSize);
    }

    public static AsyncGroup create(boolean clientMode, String threadNameFormat, int threads, ExecutorService workExecutor, ByteBufferPool safeBufferPool) {
        return new AsyncIOGroup(clientMode, threadNameFormat, threads, workExecutor, safeBufferPool);
    }

    public CompletableFuture<AsyncConnection> createTCPClient(final SocketAddress address) {
        return createTCPClient(address, 0, 0);
    }

    public abstract CompletableFuture<AsyncConnection> createTCPClient(final SocketAddress address, final int readTimeoutSeconds, final int writeTimeoutSeconds);

    public CompletableFuture<AsyncConnection> createUDPClient(final SocketAddress address) {
        return createUDPClient(address, 0, 0);
    }

    public abstract CompletableFuture<AsyncConnection> createUDPClient(final SocketAddress address, final int readTimeoutSeconds, final int writeTimeoutSeconds);

    public CompletableFuture<AsyncConnection> createClient(final boolean tcp, final SocketAddress address) {
        return tcp ? createTCPClient(address) : createUDPClient(address);
    }

    public CompletableFuture<AsyncConnection> createClient(final boolean tcp, final SocketAddress address, final int readTimeoutSeconds, final int writeTimeoutSeconds) {
        return tcp ? createTCPClient(address, readTimeoutSeconds, writeTimeoutSeconds) : createUDPClient(address, readTimeoutSeconds, writeTimeoutSeconds);
    }

    public abstract ScheduledFuture scheduleTimeout(Runnable callable, long delay, TimeUnit unit);

    public abstract AsyncGroup start();

    public abstract AsyncGroup close();

}
