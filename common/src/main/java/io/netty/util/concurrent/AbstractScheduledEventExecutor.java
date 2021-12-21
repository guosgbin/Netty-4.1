/*
 * Copyright 2015 The Netty Project
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
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PriorityQueue;

import static io.netty.util.concurrent.ScheduledFutureTask.deadlineNanos;

import java.util.Comparator;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link EventExecutor}s that want to support scheduling.
 *
 * 实现延迟或者周期性的任务
 */
public abstract class AbstractScheduledEventExecutor extends AbstractEventExecutor {
    private static final Comparator<ScheduledFutureTask<?>> SCHEDULED_FUTURE_TASK_COMPARATOR =
            new Comparator<ScheduledFutureTask<?>>() {
                @Override
                public int compare(ScheduledFutureTask<?> o1, ScheduledFutureTask<?> o2) {
                    return o1.compareTo(o2);
                }
            };

   static final Runnable WAKEUP_TASK = new Runnable() {
       @Override
       public void run() { } // Do nothing
    };

    // 任务优先队列
    PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue;

    long nextTaskId;

    protected AbstractScheduledEventExecutor() {
    }

    protected AbstractScheduledEventExecutor(EventExecutorGroup parent) {
        super(parent);
    }

    /**
     * 返回当前 Netty 程序执行了多长时间
     */
    protected static long nanoTime() {
        return ScheduledFutureTask.nanoTime();
    }

    /**
     * Given an arbitrary deadline {@code deadlineNanos}, calculate the number of nano seconds from now
     * {@code deadlineNanos} would expire.
     *
     * 计算当前时间到指定截止时间 deadlineNanos ，还剩余多少时间
     *
     * @param deadlineNanos An arbitrary deadline in nano seconds.
     * @return the number of nano seconds from now {@code deadlineNanos} would expire.
     */
    protected static long deadlineToDelayNanos(long deadlineNanos) {
        return ScheduledFutureTask.deadlineToDelayNanos(deadlineNanos);
    }

    /**
     * The initial value used for delay and computations based upon a monatomic time source.
     * @return initial value used for delay and computations based upon a monatomic time source.
     */
    protected static long initialNanoTime() {
        return ScheduledFutureTask.initialNanoTime();
    }

    /**
     * 获得任务优先队列，没有的话就创建一个默认的优先队列 DefaultPriorityQueue
     */
    PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue() {
        if (scheduledTaskQueue == null) {
            scheduledTaskQueue = new DefaultPriorityQueue<ScheduledFutureTask<?>>(
                    SCHEDULED_FUTURE_TASK_COMPARATOR,
                    // Use same initial capacity as java.util.PriorityQueue
                    11);
        }
        return scheduledTaskQueue;
    }

    private static boolean isNullOrEmpty(Queue<ScheduledFutureTask<?>> queue) {
        return queue == null || queue.isEmpty();
    }

    /**
     * Cancel all scheduled tasks.
     *
     * This method MUST be called only when {@link #inEventLoop()} is {@code true}.
     *
     * 取消所有任务
     */
    protected void cancelScheduledTasks() {
        assert inEventLoop();
        PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        if (isNullOrEmpty(scheduledTaskQueue)) {
            return;
        }

        final ScheduledFutureTask<?>[] scheduledTasks =
                scheduledTaskQueue.toArray(new ScheduledFutureTask<?>[0]);

        // 循环遍历所有的任务并取消
        for (ScheduledFutureTask<?> task: scheduledTasks) {
            // 取消任务
            task.cancelWithoutRemove(false);
        }

        scheduledTaskQueue.clearIgnoringIndexes();
    }

    /**
     * 返回已经到了截止时间的计划任务，即准备执行的 Runnable，如果没有，那么返回 null
     *
     * @see #pollScheduledTask(long)
     */
    protected final Runnable pollScheduledTask() {
        return pollScheduledTask(nanoTime());
    }

    /**
     * Return the {@link Runnable} which is ready to be executed with the given {@code nanoTime}.
     * You should use {@link #nanoTime()} to retrieve the correct {@code nanoTime}.
     *
     * 返回准备用给定的 nanoTime 内要执行执行的计划任务 Runnable。
     */
    protected final Runnable pollScheduledTask(long nanoTime) {
        assert inEventLoop();

        // 查看队首任务对象 也就是最早需要执行的任务
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        // 假如队首没有任务，或者队首的任务的执行时间还未到，直接返回null
        if (scheduledTask == null || scheduledTask.deadlineNanos() - nanoTime > 0) {
            return null;
        }
        // 移除队首的任务
        scheduledTaskQueue.remove();
        // 将这个计划任务的截止时间设置为 0
        scheduledTask.setConsumed();
        return scheduledTask;
    }

    /**
     * Return the nanoseconds until the next scheduled task is ready to be run or {@code -1} if no task is scheduled.
     *
     * 假如队首有任务对象，返回该任务对象的执行的剩余时间的纳秒值
     * 假如队首没有任务对象 返回-1
     */
    protected final long nextScheduledTaskNano() {
        // 查看队首任务对象
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        return scheduledTask != null ? scheduledTask.delayNanos() : -1;
    }

    /**
     * Return the deadline (in nanoseconds) when the next scheduled task is ready to be run or {@code -1}
     * if no task is scheduled.
     * 获取 可调用任务 的执行截至时间
     *
     * 假如队首有任务对象，返回该任务对象的 执行时间的纳秒值
     * 假如队首没有任务对象 返回-1
     */
    protected final long nextScheduledTaskDeadlineNanos() {
        // 查看堆顶任务对象
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        return scheduledTask != null ? scheduledTask.deadlineNanos() : -1;
    }

    /**
     * 获取优先级队列的头节点,即最早需要执行的计划任务，有可能是 null
     */
    final ScheduledFutureTask<?> peekScheduledTask() {
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        return scheduledTaskQueue != null ? scheduledTaskQueue.peek() : null;
    }

    /**
     * Returns {@code true} if a scheduled task is ready for processing.
     */
    protected final boolean hasScheduledTasks() {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        return scheduledTask != null && scheduledTask.deadlineNanos() <= nanoTime();
    }

    /**
     * 发布延迟任务
     *
     * @param command 任务对象
     * @param delay 延迟时间
     * @param unit 时间单位
     * @return
     */
    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (delay < 0) {
            delay = 0;
        }
        validateScheduled0(delay, unit);

        return schedule(new ScheduledFutureTask<Void>(
                this,
                command,
                deadlineNanos(unit.toNanos(delay))));
    }

    /**
     * 发布延迟任务
     *
     * @param callable 任务对象
     * @param delay 延迟时间
     * @param unit 时间单位
     * @return
     */
    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(callable, "callable");
        ObjectUtil.checkNotNull(unit, "unit");
        if (delay < 0) {
            delay = 0;
        }
        validateScheduled0(delay, unit);

        return schedule(new ScheduledFutureTask<V>(this, callable, deadlineNanos(unit.toNanos(delay))));
    }

    /**
     * 发布固定周期的周期性任务
     *
     * @param command
     * @param initialDelay
     * @param period
     * @param unit
     * @return
     */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (period <= 0) {
            throw new IllegalArgumentException(
                    String.format("period: %d (expected: > 0)", period));
        }
        validateScheduled0(initialDelay, unit);
        validateScheduled0(period, unit);

        // 因为是 固定周期， 所以 period 是正数 unit.toNanos(period)
        return schedule(new ScheduledFutureTask<Void>(
                this, command, deadlineNanos(unit.toNanos(initialDelay)), unit.toNanos(period)));
    }

    /**
     * 发布固定延时的周期性任务
     *
     * @param command 任务对象
     * @param initialDelay 任务开始的延迟时间
     * @param delay 周期时间
     * @param unit 单位
     */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (delay <= 0) {
            throw new IllegalArgumentException(
                    String.format("delay: %d (expected: > 0)", delay));
        }

        validateScheduled0(initialDelay, unit);
        validateScheduled0(delay, unit);

        return schedule(new ScheduledFutureTask<Void>(
                this, command, deadlineNanos(unit.toNanos(initialDelay)), -unit.toNanos(delay)));
    }

    @SuppressWarnings("deprecation")
    private void validateScheduled0(long amount, TimeUnit unit) {
        validateScheduled(amount, unit);
    }

    /**
     * Sub-classes may override this to restrict the maximal amount of time someone can use to schedule a task.
     *
     * @deprecated will be removed in the future.
     */
    @Deprecated
    protected void validateScheduled(long amount, TimeUnit unit) {
        // NOOP
    }

    /**
     * 将这个计划任务 task 添加到计划任务优先级队列中
     */
    final void scheduleFromEventLoop(final ScheduledFutureTask<?> task) {
        // nextTaskId a long and so there is no chance it will overflow back to 0
        scheduledTaskQueue().add(task.setId(++nextTaskId));
    }

    private <V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) {
        if (inEventLoop()) {
            // 如果当前线程就是Executor的线程，那么直接将计划任务 task 添加到计划任务队列中
            scheduleFromEventLoop(task);
        } else { // 当前执行的线程不是 Executor 所在的线程
            // 获取计划任务的截止时间
            final long deadlineNanos = task.deadlineNanos();
            // task will add itself to scheduled task queue when run if not expired
            if (beforeScheduledTaskSubmitted(deadlineNanos)) {
                // 如果任务未过期，则通过 execute 方法在运行时将自己添加到计划任务队列中
                execute(task);
            } else {
                // 如果任务已经过期，通过 lazyExecute 方法将在运行时从计划任务队列中删除自己
                lazyExecute(task);
                // Second hook after scheduling to facilitate race-avoidance
                if (afterScheduledTaskSubmitted(deadlineNanos)) {
                    execute(WAKEUP_TASK);
                }
            }
        }

        return task;
    }

    /**
     * 从计划任务队列中删除 计划任务task
     */
    final void removeScheduled(final ScheduledFutureTask<?> task) {
        assert task.isCancelled();
        if (inEventLoop()) {
            // 将计划任务从计划任务队列中删除
            scheduledTaskQueue().removeTyped(task);
        } else {
            // task will remove itself from scheduled task queue when it runs
            // 任务将在运行时从计划任务队列中删除自己
            lazyExecute(task);
        }
    }

    /**
     * Called from arbitrary non-{@link EventExecutor} threads prior to scheduled task submission.
     * Returns {@code true} if the {@link EventExecutor} thread should be woken immediately to
     * process the scheduled task (if not already awake).
     * <p>
     * If {@code false} is returned, {@link #afterScheduledTaskSubmitted(long)} will be called with
     * the same value <i>after</i> the scheduled task is enqueued, providing another opportunity
     * to wake the {@link EventExecutor} thread if required.
     *
     * @param deadlineNanos deadline of the to-be-scheduled task
     *     relative to {@link AbstractScheduledEventExecutor#nanoTime()}
     * @return {@code true} if the {@link EventExecutor} thread should be woken, {@code false} otherwise
     */
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        return true;
    }

    /**
     * See {@link #beforeScheduledTaskSubmitted(long)}. Called only after that method returns false.
     *
     * @param deadlineNanos relative to {@link AbstractScheduledEventExecutor#nanoTime()}
     * @return  {@code true} if the {@link EventExecutor} thread should be woken, {@code false} otherwise
     */
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        return true;
    }
}
