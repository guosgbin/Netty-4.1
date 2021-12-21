/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The {@link EventExecutorGroup} is responsible for providing the {@link EventExecutor}'s to use
 * via its {@link #next()} method. Besides this, it is also responsible for handling their
 * life-cycle and allows shutting them down in a global fashion.
 *
 * EventExecutorGroup通过next方法提供 EventExecutor 实例
 * 除此之外，EventExecutorGroup还负责处理它们的生命周期并允许以全局方式关闭它们。
 *
 * =====================================================================
 * 这个接口提供了一些关闭相关的方法
 *
 * (1) shutdownGracefully 系列方法，这是 EventExecutorGroup 提供最关键的方法，优雅地关闭执行器。
 * 本来 EventExecutorGroup 继承自 ScheduledExecutorService 有两个关闭执行器的方法 shutdown() 和 shutdownNow()。
 * shutdownGracefully 方法与它们的区别是在执行器关闭之前的这段时间内，如果有任务添加，它也保证任务被接收。
 *
 * (2) isShuttingDown() 表示执行器正在进行关闭，即调用了 shutdown 系列的方法。
 * 继承自 ScheduledExecutorService 的方法中，只有 isShutdown() 表示执行器已经被关闭，isTerminated() 执行器已经被关闭且所有任务都完成了。
 *
 * (3) terminationFuture() 返回一个执行器被完全终止的通知Future。
 *
 * (4) EventExecutor next() 和 Iterator<EventExecutor> iterator() ：用于返回该 EventExecutorGroup 所管理 EventExecutor 实例。
 */
public interface EventExecutorGroup extends ScheduledExecutorService, Iterable<EventExecutor> {

    /**
     * Returns {@code true} if and only if all {@link EventExecutor}s managed by this {@link EventExecutorGroup}
     * are being {@linkplain #shutdownGracefully() shut down gracefully} or was {@linkplain #isShutdown() shut down}.
     *
     * 当且仅当该 EventExecutorGroup 管理的所有 EventExecutor
     * 都使用 `shutdownGracefully()` 或者 `isShutdown()` 关闭时 才返回true。
     */
    boolean isShuttingDown();

    /**
     * Shortcut method for {@link #shutdownGracefully(long, long, TimeUnit)} with sensible default values.
     *
     * 优雅地关闭 EventExecutorGroup，使用默认值调用 shutdownGracefully(long, long, TimeUnit) 方法
     * 返回值就是 terminationFuture() 方法返回值
     *
     * @return the {@link #terminationFuture()}
     */
    Future<?> shutdownGracefully();

    /**
     * Signals this executor that the caller wants the executor to be shut down.  Once this method is called,
     * {@link #isShuttingDown()} starts to return {@code true}, and the executor prepares to shut itself down.
     * Unlike {@link #shutdown()}, graceful shutdown ensures that no tasks are submitted for <i>'the quiet period'</i>
     * (usually a couple seconds) before it shuts itself down.  If a task is submitted during the quiet period,
     * it is guaranteed to be accepted and the quiet period will start over.
     *
     * 向此执行程序发出信号，表明调用者希望关闭执行程序。
     * 一旦调用此方法， isShuttingDown()开始返回true ，并且执行程序准备关闭自己。
     * 与shutdown()不同，优雅关机确保在它自己关闭之前，在“安静期” （通常是几秒钟）内不会提交任何任务。
     * 如果在静默期提交任务，则保证被接受，静默期将重新开始
     *
     * @param quietPeriod the quiet period as described in the documentation
     * @param timeout     the maximum amount of time to wait until the executor is {@linkplain #shutdown()}
     *                    regardless if a task was submitted during the quiet period
     * @param unit        the unit of {@code quietPeriod} and {@code timeout}
     *
     * @return the {@link #terminationFuture()}
     */
    Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit);

    /**
     * Returns the {@link Future} which is notified when all {@link EventExecutor}s managed by this
     * {@link EventExecutorGroup} have been terminated.
     *
     * 当该 EventExecutorGroup 管理的所有 EventExecutor 被终止时，该Future会被通知。
     */
    Future<?> terminationFuture();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    void shutdown();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    List<Runnable> shutdownNow();

    /**
     * Returns one of the {@link EventExecutor}s managed by this {@link EventExecutorGroup}.
     *
     * 返回该 EventExecutorGroup 所管理的一个 EventExecutor 实例
     */
    EventExecutor next();

    @Override
    Iterator<EventExecutor> iterator();

    @Override
    Future<?> submit(Runnable task);

    @Override
    <T> Future<T> submit(Runnable task, T result);

    @Override
    <T> Future<T> submit(Callable<T> task);

    @Override
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    @Override
    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
