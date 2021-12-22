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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Default {@link SingleThreadEventExecutor} implementation which just execute all submitted task in a
 * serial fashion.
 *
 * 默认的SingleThreadEventExecutor实现，它只是以串行方式执行所有提交的任务。
 */
public final class DefaultEventExecutor extends SingleThreadEventExecutor {

    public DefaultEventExecutor() {
        this((EventExecutorGroup) null);
    }

    public DefaultEventExecutor(ThreadFactory threadFactory) {
        this(null, threadFactory);
    }

    public DefaultEventExecutor(Executor executor) {
        this(null, executor);
    }

    public DefaultEventExecutor(EventExecutorGroup parent) {
        this(parent, new DefaultThreadFactory(DefaultEventExecutor.class));
    }

    public DefaultEventExecutor(EventExecutorGroup parent, ThreadFactory threadFactory) {
        super(parent, threadFactory, true);
    }

    public DefaultEventExecutor(EventExecutorGroup parent, Executor executor) {
        super(parent, executor, true);
    }

    public DefaultEventExecutor(EventExecutorGroup parent, ThreadFactory threadFactory, int maxPendingTasks,
                                RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, threadFactory, true, maxPendingTasks, rejectedExecutionHandler);
    }

    public DefaultEventExecutor(EventExecutorGroup parent, Executor executor, int maxPendingTasks,
                                RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, true, maxPendingTasks, rejectedExecutionHandler);
    }

    @Override
    protected void run() {
        // 利用死循环，获取任务队列中的任务
        for (;;) {
            System.out.println(Thread.currentThread() + "==========");
            // 获取任务，如果任务队列为空，将会被阻塞
            // 直到有任务添加，或者线程被中断
            Runnable task = takeTask();
            if (task != null) {
                task.run();
                updateLastExecutionTime();
            }

            // 这里的判断非常重要，当执行器是开始 Shutdown 之后的状态，
            // 那么就要跳出死循环了
            if (confirmShutdown()) {
                break;
            }
        }
    }

    public static void main(String[] agrs) {
        // 池子只给1个线程，下面只会打印一个数据。
        // 给5个线程就好了
//        final Executor executor = Executors.newFixedThreadPool(1);
        final Executor executor = Executors.newFixedThreadPool(5);

        for (int index = 0; index < 5; index++) {
            final int tempIndex = index;
            DefaultEventExecutor eventExecutor = new DefaultEventExecutor(executor);
            System.out.println("index: "+tempIndex);
            eventExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    System.out.println("thread: "+Thread.currentThread()+"   开始 index:"+ tempIndex);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    System.out.println("thread: "+Thread.currentThread()+"   结束  index:"+ tempIndex);
                }
            });
        }
    }
}
