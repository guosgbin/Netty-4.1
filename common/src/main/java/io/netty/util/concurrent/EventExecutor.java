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

/**
 * The {@link EventExecutor} is a special {@link EventExecutorGroup} which comes
 * with some handy methods to see if a {@link Thread} is executed in a event loop.
 * Besides this, it also extends the {@link EventExecutorGroup} to allow for a generic
 * way to access methods.
 *
 * EventExecutor是一个特殊的EventExecutorGroup ，
 * 它带有一些方便的方法来查看Thread是否在事件循环中执行。
 *
 * =============================================================
 * EventExecutor 接口继承自 EventExecutorGroup 接口，
 * 它提供最主要的方法就是 inEventLoop，判断给定线程是不是在事件循环中执行。
 *
 */
public interface EventExecutor extends EventExecutorGroup {

    /**
     * Returns a reference to itself.
     *
     * 返回自己
     */
    @Override
    EventExecutor next();

    /**
     * Return the {@link EventExecutorGroup} which is the parent of this {@link EventExecutor},
     *
     * 返回管理该事件执行器EventExecutor的父EventExecutorGroup
     */
    EventExecutorGroup parent();

    /**
     * Calls {@link #inEventLoop(Thread)} with {@link Thread#currentThread()} as argument
     *
     * 相当于 inEventLoop(Thread.currentThread())
     */
    boolean inEventLoop();

    /**
     * Return {@code true} if the given {@link Thread} is executed in the event loop,
     * {@code false} otherwise.
     *
     * 如果给定的Thread在事件循环中执行，则返回true否则返回false 。
     */
    boolean inEventLoop(Thread thread);

    /**
     * Return a new {@link Promise}.
     *
     * 返回一个新的 Promise 实例
     */
    <V> Promise<V> newPromise();

    /**
     * Create a new {@link ProgressivePromise}.
     *
     * 返回一个新的 ProgressivePromise 实例
     */
    <V> ProgressivePromise<V> newProgressivePromise();

    /**
     * Create a new {@link Future} which is marked as succeeded already. So {@link Future#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     *
     * 创建一个新的已经标记为成功的 Future，所以Future.isSuccess()将返回true 。
     * 所有添加到它的FutureListener都会被直接通知。 此外，所有阻塞方法的调用都不会阻塞直接返回
     */
    <V> Future<V> newSucceededFuture(V result);

    /**
     * Create a new {@link Future} which is marked as failed already. So {@link Future#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     *
     * 创建一个新的已标记为失败的Future，所以Future.isSuccess()将返回false 。
     * 所有添加到它的FutureListener都会被直接通知。 此外，每次调用阻塞方法都会返回而不会阻塞
     */
    <V> Future<V> newFailedFuture(Throwable cause);
}
