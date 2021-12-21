/*
 * Copyright 2013 The Netty Project
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

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    // 我们不管是延时任务还是周期性任务都有任务执行的截止时间 deadlineNanos，截止时间到了就会执行任务。
    // 所以 netty 设置了一个基准时间 START_TIME
    private static final long START_TIME = System.nanoTime();

    /**
     * 程序执行了多少纳秒值。因为是当前纳秒值和程序开始时间纳秒值之差
     * @return
     */
    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    /**
     * 将用户自定义的延时时间 转换成 截止时间 deadlineNanos，
     * 即 System.nanoTime() - START_TIME + delay
     */
    static long deadlineNanos(long delay) {
        long deadlineNanos = nanoTime() + delay;
        // Guard against overflow
        return deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    static long initialNanoTime() {
        return START_TIME;
    }

    // set once when added to priority queue
    private long id;

    private long deadlineNanos;
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    // 0 不重复
    // > 0 以固定速率重复  周期任务
    // < 0 以固定延迟重复  延迟任务
    private final long periodNanos;

    private int queueIndex = INDEX_NOT_IN_QUEUE;

    /**
     * 创建一个延迟任务
     *
     * @param executor
     * @param runnable
     * @param nanoTime
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Runnable runnable, long nanoTime) {

        super(executor, runnable);
        deadlineNanos = nanoTime;
        // 因为是延迟任务 所以为0
        periodNanos = 0;
    }

    /**
     * 创建周期性任务
     *
     * @param executor
     * @param runnable
     * @param nanoTime
     * @param period
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Runnable runnable, long nanoTime, long period) {

        super(executor, runnable);
        deadlineNanos = nanoTime;
        periodNanos = validatePeriod(period);
    }

    /**
     * 创建周期性任务
     *
     * @param executor
     * @param callable
     * @param nanoTime
     * @param period
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = validatePeriod(period);
    }

    /**
     * 创建一个延迟任务
     *
     * @param executor
     * @param callable
     * @param nanoTime
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    private static long validatePeriod(long period) {
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        return period;
    }

    ScheduledFutureTask<V> setId(long id) {
        if (this.id == 0L) {
            this.id = id;
        }
        return this;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    void setConsumed() {
        // Optimization to avoid checking system clock again
        // after deadline has passed and task has been dequeued
        if (periodNanos == 0) {
            assert nanoTime() >= deadlineNanos;
            deadlineNanos = 0L;
        }
    }

    /**
     * 当前剩余的时间
     * 即 使用截止时间deadlineNanos - 当前时间真实值 nanoTime()
     */
    public long delayNanos() {
        return deadlineToDelayNanos(deadlineNanos());
    }

    /**
     * 截止时间转换成剩余时间
     */
    static long deadlineToDelayNanos(long deadlineNanos) {
        return deadlineNanos == 0L ? 0L : Math.max(0L, deadlineNanos - nanoTime());
    }

    /**
     * 获取到指定时间戳 currentTimeNanos 时，还剩余的时间纳秒值
     * 即 截止时间deadlineNanos - ( currentTimeNanos - START_TIME)
     */
    public long delayNanos(long currentTimeNanos) {
        return deadlineNanos == 0L ? 0L
                : Math.max(0L, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else {
            assert id != that.id;
            return 1;
        }
    }

    /**
     * 运行任务
     */
    @Override
    public void run() {
        // 当前线程是否是 Executor 执行器的线程
        assert executor().inEventLoop();
        try {
            // delayNanos() > 0L 表示任务截止时间还没有到
            if (delayNanos() > 0L) {
                // Not yet expired, need to add or remove from queue
                if (isCancelled()) {
                    // 任务已经被取消，那么就从列表中移除
                    scheduledExecutor().scheduledTaskQueue().removeTyped(this);
                } else {
                    // 否则将任务重新放回队列
                    scheduledExecutor().scheduleFromEventLoop(this);
                }
                return;
            }
            // 走到此处前置条件 任务截止时间已经到了
            if (periodNanos == 0) {
                // periodNanos == 0 表示只是延时任务。
                // 先将任务设置成不可取消
                if (setUncancellableInternal()) {
                    V result = runTask();
                    // 设置 PromiseTask 为成功，进行通知
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                if (!isCancelled()) { // 检查任务是否被取消
                    runTask();
                    if (!executor().isShutdown()) { // 判断执行器是否被终止
                        if (periodNanos > 0) {
                            // periodNanos > 0 表示固定周期，那么下一次执行时间就是
                            // 本次截止时间deadlineNanos + 周期时间 periodNanos
                            // 但是这个值可能小于当前时间啊，只要任务执行时间比周期时间 periodNanos大，
                            // 那么这个值就小于当前时间。就代表会立即运行
                            deadlineNanos += periodNanos;
                        } else {
                            // periodNanos < 0 表示固定延时。
                            // 使用当前时间 nanoTime() 加上固定延时时间(- periodNanos)
                            deadlineNanos = nanoTime() - periodNanos;
                        }
                        if (!isCancelled()) {
                            scheduledExecutor().scheduledTaskQueue().add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            setFailureInternal(cause);
        }
    }

    private AbstractScheduledEventExecutor scheduledExecutor() {
        return (AbstractScheduledEventExecutor) executor();
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
            scheduledExecutor().removeScheduled(this);
        }
        return canceled;
    }

    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i;
    }
}
