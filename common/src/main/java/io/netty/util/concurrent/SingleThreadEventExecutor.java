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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Abstract base class for {@link OrderedEventExecutor}'s that execute all its submitted tasks in a single thread.
 *
 * 单个线程的事件执行器
 *
 * 如何完整实现一个事件执行器?
 * （1）要有一个待执行任务队列 taskQueue，存储所有要执行的任务。
 * （2）能将过期的计划任务添加到待执行任务队列 taskQueue。
 * （3）开启一个线程，与当前事件执行器绑定，这个线程轮询任务队列，运行任务，这样保证属于此事件执行器的任务都在执行器线程中运行。
 * （4）能够优雅地关闭执行器，这里就要给执行器分不同状态。
 *
 */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    // 执行器分为5个状态: ST_TERMINATED > ST_SHUTDOWN > ST_SHUTTING_DOWN > ST_STARTED > ST_NOT_STARTED

    // 表示执行器还未开始运行
    private static final int ST_NOT_STARTED = 1;
    // 表示执行器正在运行
    private static final int ST_STARTED = 2;
    // 表示执行器开始 shutdown
    private static final int ST_SHUTTING_DOWN = 3;
    // 表示执行器已经 shutdown
    private static final int ST_SHUTDOWN = 4;
    // 表示执行器已经 terminated
    private static final int ST_TERMINATED = 5;

    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    // CAS更新 state 字段
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");
    // CAS更新 threadProperties
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    // 任务队列
    private final Queue<Runnable> taskQueue;

    private volatile Thread thread;
    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties;
    // 这个是包装过的executor
    private final Executor executor;
    private volatile boolean interrupted;

    private final CountDownLatch threadLock = new CountDownLatch(1);
    // 关闭的钩子任务
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    // 是否通过向任务队列 taskQueue 中添加唤醒任务 WAKEUP_TASK，来唤醒阻塞线程
    private final boolean addTaskWakesUp;
    // 待执行任务(即挂起任务)的最大值，超过这个值，那么添加新任务会被直接拒绝。
    private final int maxPendingTasks;
    // 拒绝策略
    private final RejectedExecutionHandler rejectedExecutionHandler;

    private long lastExecutionTime;

    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED;

    // 优雅关闭的静默期
    private volatile long gracefulShutdownQuietPeriod;
    // 优雅关闭的超时时间
    private volatile long gracefulShutdownTimeout;
    // 开始shutdown操作的时间
    private long gracefulShutdownStartTime;

    // 表示执行器已经关闭的Future对象
    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     *                          是否通过向任务队列 taskQueue 中添加唤醒任务 WAKEUP_TASK，来唤醒阻塞线程
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     *                          待执行任务(即挂起任务)的最大值，超过这个值，那么添加新任务会被直接拒绝
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, int maxPendingTasks,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        // 封装一下 Executor
        this.executor = ThreadExecutorMap.apply(executor, this);
        taskQueue = newTaskQueue(this.maxPendingTasks);
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, Queue<Runnable> taskQueue,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = DEFAULT_MAX_PENDING_EXECUTOR_TASKS;
        // 封装一下 Executor
        this.executor = ThreadExecutorMap.apply(executor, this);
        this.taskQueue = ObjectUtil.checkNotNull(taskQueue, "taskQueue");
        this.rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * @deprecated Please use and override {@link #newTaskQueue(int)}.
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     *
     * 创建一个新的Queue ，该Queue将保存要执行的任务。
     * 此默认实现将返回LinkedBlockingQueue，但如果您的SingleThreadEventExecutor子类不会在此Queue上执行任何阻塞调用，
     * 那么重写这个方法并返回一些根本不支持阻塞操作的性能更高的实现可能是有意义的。
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    /**
     * Interrupt the current running {@link Thread}.
     * 中断当前运行的Thread 。
     */
    protected void interruptThread() {
        Thread currentThread = thread;
        if (currentThread == null) {
            interrupted = true;
        } else {
            currentThread.interrupt();
        }
    }

    /**
     * 不阻塞获取任务
     * 获取并移除队首任务，如果该任务队列为空则返回 null。
     *
     * @see Queue#poll()
     */
    protected Runnable pollTask() {
        assert inEventLoop();
        return pollTaskFrom(taskQueue);
    }

    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) {
        // 死循环，用于排除唤醒任务 WAKEUP_TASK
        for (;;) {
            Runnable task = taskQueue.poll();
            if (task != WAKEUP_TASK) {
                // 只要不是唤醒任务，哪怕 task == null，都直接返回
                return task;
            }
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * 从任务队列中取出下一个Runnable ，如果当前没有任务存在，则将阻塞。
     * 请注意，如果通过newTaskQueue()创建的任务队列没有实现BlockingQueue接口 ，则此方法将抛出UnsupportedOperationException
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     *                      如果执行程序线程被中断或唤醒则返回null
     *
     * =======================
     * （1）下一个 scheduled 任务 scheduledTask 没有，那么直接调用 taskQueue.take() 一直阻塞等待，直到有待执行任务添加或者线程被中断。
     *
     * 可能有人有疑惑，如果在 taskQueue.take()等待期间，有人添加了schedule任务，那怎么办？
     *     其实在 AbstractScheduledEventExecutor 的 schedule(final ScheduledFutureTask<V> task) 方法实现中，
     * 如果不是执行器线程添加计划任务，它会直接调用execute(task), 将schedule任务当成一个待执行任务添加到待执行任务队列中，唤醒线程。
     *
     * 在 scheduled 任务 ScheduledFutureTask 的run 方法中，会判断如果截止时间没有到，再把自己添加到 scheduled 任务队列中，
     * 而这时它一定是在执行器线程。
     *
     * （2）下一个计划任务 scheduledTask 还没有过期，
     * 那么调用 taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS) 超时等待有没有任务。
     *
     * （3）下一个计划任务 scheduledTask 已经过期，
     * 调用 fetchFromScheduledTaskQueue() 方法将过期的计划任务添加到待处理任务队列中。
     */
    protected Runnable takeTask() {
        assert inEventLoop();
        // 假如任务队列不是 BlockingQueue 阻塞队列，直接抛出异常
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        // 使用死循环保证获取下一个任务，如果当前没有任务，就一直阻塞线程。
        // 除非有任务添加，或者执行线程被中断或唤醒，
        for (;;) {
            /*
             * 因为执行器里面既有scheduled任务，又有待执行的任务。
             * 所以获取下一个执行任务，既要考虑待执行任务队列，又要考虑scheduled任务队列。
             * 看一下是否有已过期的scheduled任务，那么就将这个已过期scheduled任务加入到待执行任务队列。
             */
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                // 如果没有scheduled任务，那么就直接从待执行任务队列获取任务
                Runnable task = null;
                try {
                    // 从待执行任务队列获取任务，
                    // 如果队列为空，那么线程将被阻塞
                    // 直到有任务添加，或者有人中断线程
                    task = taskQueue.take();
                    // 如果是唤醒任务，直接返回 null
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                // 如果有人中断线程，这里 task 也为空，不然就是执行任务
                return task;
            } else {
                // 如果有scheduled任务，获取下一个计划任务剩余时间 delayNanos
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) { // 如果下一个计划任务还有剩余时间
                    try {
                        // 从待执行任务队列超时获取任务，超时时间就是计划任务的剩余时间
                        // 如果队列为空，那么线程将被阻塞
                        // 直到有任务添加，超时时间到了或者有人中断线程。
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    // 将scheduled任务队列中当前过期的Scheduled任务添加到 待执行任务队列 taskQueue
                    // 注意如果taskQueue中总是有一个任务，计划任务可能永远不会执行。
                    fetchFromScheduledTaskQueue();
                    // 因为已经将时间到了的scheduled任务加入到任务队列 taskQueue 中，
                    // 这里重新获取任务，不会阻塞线程
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    /**
     * 将 Scheduled 任务队列中当前过期的任务添加到 待执行任务队列 taskQueue
     */
    private boolean fetchFromScheduledTaskQueue() {
        // 计划任务队列为空，直接返回
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return true;
        }
        // 得到当前Netty程序执行了多久的时间
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        for (;;) {
            // 获取调度任务 优先级队列队首结点，可能返回null
            Runnable scheduledTask = pollScheduledTask(nanoTime);
            if (scheduledTask == null) {
                // 说明没有截止时间到了计划任务，直接返回
                return true;
            }
            if (!taskQueue.offer(scheduledTask)) {
                // 普通队列放不下了，将任务重新返回到调度队列里去
                // 以便我们可以再次获取它
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                scheduledTaskQueue.add((ScheduledFutureTask<?>) scheduledTask);
                return false;
            }
        }
    }

    /**
     * @return {@code true} if at least one scheduled task was executed.
     */
    private boolean executeExpiredScheduledTasks() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return false;
        }
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        if (scheduledTask == null) {
            return false;
        }
        do {
            safeExecute(scheduledTask);
        } while ((scheduledTask = pollScheduledTask(nanoTime)) != null);
        return true;
    }

    /**
     * 查看待执行任务队列头任务。如果队列为空，返回 null，且不会阻塞线程
     *
     * @see Queue#peek()
     */
    protected Runnable peekTask() {
        assert inEventLoop();
        return taskQueue.peek();
    }

    /**
     * 判断待执行任务队列是否为空
     * @see Queue#isEmpty()
     */
    protected boolean hasTasks() {
        assert inEventLoop();
        return !taskQueue.isEmpty();
    }

    /**
     * 返回待执行任务队列的数量。
     *
     * Return the number of tasks that are pending for processing.
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    protected void addTask(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        if (!offerTask(task)) {
            reject(task);
        }
    }

    final boolean offerTask(Runnable task) {
        if (isShutdown()) {
            reject();
        }
        return taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     */
    protected boolean removeTask(Runnable task) {
        return taskQueue.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * 轮询任务队列中的所有任务，并通过 Runnable.run() 方法运行它们。
     *
     * @return {@code true} if and only if at least one task was run
     */
    protected boolean runAllTasks() {
        assert inEventLoop();
        boolean fetchedAll;
        // 至少有一个任务运行
        boolean ranAtLeastOne = false;

        do {
            // 将调度任务队列中需要被调度的任务 转移到 普通任务队列中 taskQueue
            // 返回值表示需要被调度的任务 有没有转移完毕
            fetchedAll = fetchFromScheduledTaskQueue();
            if (runAllTasksFrom(taskQueue)) {
                ranAtLeastOne = true;
            }
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        // 到此处表示 需要调度的任务和普通任务都执行完毕了

        if (ranAtLeastOne) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        afterRunningAllTasks();
        return ranAtLeastOne;
    }

    /**
     * Execute all expired scheduled tasks and all current tasks in the executor queue until both queues are empty,
     * or {@code maxDrainAttempts} has been exceeded.
     * @param maxDrainAttempts The maximum amount of times this method attempts to drain from queues. This is to prevent
     *                         continuous task execution and scheduling from preventing the EventExecutor thread to
     *                         make progress and return to the selector mechanism to process inbound I/O events.
     * @return {@code true} if at least one task was run.
     */
    protected final boolean runScheduledAndExecutorTasks(final int maxDrainAttempts) {
        assert inEventLoop();
        boolean ranAtLeastOneTask;
        int drainAttempt = 0;
        do {
            // We must run the taskQueue tasks first, because the scheduled tasks from outside the EventLoop are queued
            // here because the taskQueue is thread safe and the scheduledTaskQueue is not thread safe.
            ranAtLeastOneTask = runExistingTasksFrom(taskQueue) | executeExpiredScheduledTasks();
        } while (ranAtLeastOneTask && ++drainAttempt < maxDrainAttempts);

        if (drainAttempt > 0) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        afterRunningAllTasks();

        return drainAttempt > 0;
    }

    /**
     * Runs all tasks from the passed {@code taskQueue}.
     * 运行来自传递的 taskQueue 中的所有任务
     * 这个方法与 runAllTasks() 方法区别，它只会运行传递来的待处理任务队列 taskQueue 中的所有任务，不会去管当前是否有过期的计划任务
     *
     * @param taskQueue To poll and execute all tasks.
     *
     * @return {@code true} if at least one task was executed. 至少执行了一个任务，才返回 true。
     */
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
        // 从待执行任务队列中获取任务，如果队列为空，则返回 null，不会阻塞线程
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            // 没有待执行任务了，直接返回false
            return false;
        }
        // 死循环，直到执行所有的任务
        for (;;) {
            safeExecute(task);
            task = pollTaskFrom(taskQueue);
            if (task == null) {
                return true;
            }
        }
    }

    /**
     * What ever tasks are present in {@code taskQueue} when this method is invoked will be {@link Runnable#run()}.
     *
     * 当调用此方法时，`taskQueue` 中出现的任何任务都将会运行，包括唤醒任务 WAKEUP_TASK。
     * 与 runAllTasksFrom(Queue<Runnable> taskQueue) 方法区别，就是这个方法会运行队列 taskQueue 中任何任务，包括唤醒任务WAKEUP_TASK。
     *
     * @param taskQueue the task queue to drain.
     * @return {@code true} if at least {@link Runnable#run()} was called.
     */
    private boolean runExistingTasksFrom(Queue<Runnable> taskQueue) {
        // 从待执行任务队列中获取任务，如果队列为空，则返回 null，不会阻塞线程
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        int remaining = Math.min(maxPendingTasks, taskQueue.size());
        // 执行任务
        safeExecute(task);
        // Use taskQueue.poll() directly rather than pollTaskFrom() since the latter may
        // silently consume more than one item from the queue (skips over WAKEUP_TASK instances)
        // 直接使用 taskQueue.poll( )而不是 pollTaskFrom(),
        // 因为 pollTaskFrom() 会跳过唤醒任务 WAKEUP_TASK
        while (remaining-- > 0 && (task = taskQueue.poll()) != null) {
            safeExecute(task);
        }
        return true;
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     *
     * 轮询任务队列中的所有任务，并通过 Runnable.run()方法运行它们。
     * 该方法停止运行任务队列中的任务，如果它运行的时间超过timeoutNanos。
     */
    protected boolean runAllTasks(long timeoutNanos) {
        // 从定时任务队列中将达到执行事件的task转移到taskQueue队列中
        fetchFromScheduledTaskQueue();
        // 从taskQueue中获取task，如果队列为空，则返回 null，不会阻塞线程
        Runnable task = pollTask();
        if (task == null) {
            afterRunningAllTasks();
            return false;
        }

        // 表示执行任务的截止时间
        final long deadline = timeoutNanos > 0 ? ScheduledFutureTask.nanoTime() + timeoutNanos : 0;
        // 已经执行任务的个数
        long runTasks = 0;
        // 最后一个任务的执行时间
        long lastExecutionTime;
        for (;;) {
            // 执行任务
            safeExecute(task);

            runTasks ++;

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            // 每隔64个任务去查看是否超时
            if ((runTasks & 0x3F) == 0) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }

            // 从taskQueue队列中获取task，假如没有task了，则更新最后执行时间，并跳出循环
            task = pollTask();
            if (task == null) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                break;
            }
        }

        afterRunningAllTasks();
        // 记录最后一次执行任务的时间
        this.lastExecutionTime = lastExecutionTime;
        return true;
    }

    /**
     * Invoked before returning from {@link #runAllTasks()} and {@link #runAllTasks(long)}.
     * 这个方法让子类可以在每次事件执行器迭代之后，做一点额外事情。
     */
    @UnstableApi
    protected void afterRunningAllTasks() { }

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Returns the absolute point in time (relative to {@link #nanoTime()}) at which the the next
     * closest scheduled task should run.
     */
    @UnstableApi
    protected long deadlineNanos() {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return nanoTime() + SCHEDULE_PURGE_INTERVAL;
        }
        return scheduledTask.deadlineNanos();
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    protected void updateLastExecutionTime() {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * Run the tasks in the {@link #taskQueue}
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        // 注意，关闭钩子可以添加/删除关闭钩子任务。
        // 通过 while 循环，保证将所有的钩子任务都能执行
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            // 清空集合，如果在关闭期间还有人添加钩子函数，
            // 通过 while 循环，就可以继续处理了
            shutdownHooks.clear();
            // 遍历钩子方法
            for (Runnable task: copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    /**
     * 优雅的关闭执行器
     *
     * @param quietPeriod the quiet period as described in the documentation
     * @param timeout     the maximum amount of time to wait until the executor is {@linkplain #shutdown()}
     *                    regardless if a task was submitted during the quiet period
     * @param unit        the unit of {@code quietPeriod} and {@code timeout}
     *
     * @return
     */
    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        ObjectUtil.checkPositiveOrZero(quietPeriod, "quietPeriod");
        if (timeout < quietPeriod) {
            // 假如执行器的关闭的超时时间timeout小于静默期时间quietPeriod，则报错
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        ObjectUtil.checkNotNull(unit, "unit");

        // 如果当前执行器状态已经开始关闭，那么返回 terminationFuture()
        if (isShuttingDown()) {
            return terminationFuture();
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            // 如果当前执行器状态已经开始关闭，那么返回 terminationFuture()
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                // 如果当前线程是执行器线程，新的执行器状态 newState 就是 ST_SHUTTING_DOWN
                // 这是因为当前线程是 eventLoop 线程，状态改变只会在 eventLoop 线程改变，所以这里可以直接赋值
                newState = ST_SHUTTING_DOWN;
            } else {
                // 当前线程不是执行器线程，需要分情况判断
                // 因为在某个时刻 eventLoop 线程可能会改变执行器的状态
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        // 只有当原来执行器状态是 未开始 和 已开始，
                        // 新的执行器状态 newState 才是 ST_SHUTTING_DOWN
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        // 否则还维持原样
                        newState = oldState;
                        wakeup = false;
                }
            }
            // 用 CAS 的方式原子化更新执行器状态，如果不成功，那么就在 for 循环中继续
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        // 确保线程正常启动
        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }

        if (wakeup) {
            // 唤醒可能阻塞的执行器线程，执行任务
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }

        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }
    }

    /**
     * 表示当前执行器状态是开始shutdown之后，
     * 即包括 开始shutdown，完全shutdown和完全终止terminated
     */
    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    /**
     * 表示当前执行器状态是完全shutdown之后，
     * 即包括 完全shutdown和完全终止terminated
     */
    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    /**
     * 表示当前执行器状态是完全终止terminated
     */
    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * 确保运行所有剩余的任务和关闭钩子函数。
     * 返回 true，表示可以让执行器变成完成 shutdown状态，
     */
    protected boolean confirmShutdown() {
        if (!isShuttingDown()) {
            return false;
        }

        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        // 取消所有Scheduled任务
        cancelScheduledTasks();

        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        // 轮询任务队列中的所有任务 和 运行 Shutdown 的钩子任务
        if (runAllTasks() || runShutdownHooks()) {
            // 执行器状态已经完全 Shutdown
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                // 执行器关闭-不再添加新的任务。
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }
            taskQueue.offer(WAKEUP_TASK);
            // 返回false，说明还有任务没有执行，不能变成 Shutdown，
            // 继续调用confirmShutdown方法
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            // 执行器状态已经完全 Shutdown
            // 或者当前时间已经超过 优雅关闭的超时时间 gracefulShutdownTimeout
            return true;
        }

        // 当前时间减去最近一次任务执行时间，还小于优雅关闭安静期时间 gracefulShutdownQuietPeriod
        // 就还可以将这段时间添加的任务运行
        // 在静默期间每100ms唤醒线程执行期间提交的任务
        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            // 检查每100毫秒是否有任务被添加到队列中。
            taskQueue.offer(WAKEUP_TASK);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            // 返回false，说明还有任务没有执行，不能变成 Shutdown，
            // 继续调用confirmShutdown方法
            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        // 静默时间内没有任务提交，可以优雅关闭，此时若用户又提交任务则不会被执行
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ObjectUtil.checkNotNull(unit, "unit");
        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        threadLock.await(timeout, unit);

        return isTerminated();
    }

    @Override
    public void execute(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        execute(task, !(task instanceof LazyRunnable) && wakesUpForTask(task));
    }

    @Override
    public void lazyExecute(Runnable task) {
        execute(ObjectUtil.checkNotNull(task, "task"), false);
    }

    /**
     * 创建线程，将线程添加到EventLoop的无锁化串行任务队列
     *
     * @param task
     * @param immediate
     */
    private void execute(Runnable task, boolean immediate) {
        // 判断执行当前代码的线程是否是 eventloop的线程，是则true，否则fasle
        boolean inEventLoop = inEventLoop();
        // 将任务添加到待执行任务队列taskQueue中
        // 注意这里是可以被不同线程调用的，所以有并发冲突问题。
        // 因此任务队列taskQueue 必须是一个线程安全的队列
        addTask(task);
        if (!inEventLoop) {
            // 执行当前代码的线程不是eventloop线程，需要开启线程
            // 这个方法做了判断，只有当执行器状态是 ST_NOT_STARTED 才会开启执行器线程
            startThread();
            if (isShutdown()) {
                // 如果执行器状态已经 Shutdown 之后，就要拒绝任务。
                // 注意这里的状态是已经 Shutdown 之后，所以不包括开始 Shutdown 的状态。
                boolean reject = false;
                try {
                    // 移除任务
                    if (removeTask(task)) {
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                    // The task queue does not support removal so the best thing we can do is to just move on and
                    // hope we will be able to pick-up the task before its completely terminated.
                    // In worst case we will log on termination.
                }
                if (reject) {
                    reject();
                }
            }
        }

        // 是否唤醒可能阻塞的执行器线程
        if (!addTaskWakesUp && immediate) {
            wakeup(inEventLoop);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     * If the {@link SingleThreadEventExecutor} is not started yet, this operation will start it and block until
     * it is fully started.
     */
    public final ThreadProperties threadProperties() {
        ThreadProperties threadProperties = this.threadProperties;
        if (threadProperties == null) {
            Thread thread = this.thread;
            if (thread == null) {
                assert !inEventLoop();
                submit(NOOP_TASK).syncUninterruptibly();
                thread = this.thread;
                assert thread != null;
            }

            threadProperties = new DefaultThreadProperties(thread);
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {
                threadProperties = this.threadProperties;
            }
        }

        return threadProperties;
    }

    /**
     * @deprecated use {@link AbstractEventExecutor.LazyRunnable}
     */
    @Deprecated
    protected interface NonWakeupRunnable extends LazyRunnable { }

    /**
     * Can be overridden to control which tasks require waking the {@link EventExecutor} thread
     * if it is waiting so that they can be run immediately.
     */
    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    /**
     * Offers the task to the associated {@link RejectedExecutionHandler}.
     *
     * @param task to reject.
     */
    protected final void reject(Runnable task) {
        rejectedExecutionHandler.rejected(task, this);
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    private void startThread() {
        if (state == ST_NOT_STARTED) {
            // 假如是线程未启动状态，使用CAS设置为线程启动状态
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                boolean success = false;
                try {
                    doStartThread();
                    success = true;
                } finally {
                    if (!success) {
                        STATE_UPDATER.compareAndSet(this, ST_STARTED, ST_NOT_STARTED);
                    }
                }
            }
        }
    }

    private boolean ensureThreadStarted(int oldState) {
        if (oldState == ST_NOT_STARTED) {
            try {
                doStartThread();
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * （1）这里注意，我们要保证 executor.execute 方法会在新线程中执行 Runnable 回调，必须保证每个事件执行器绑定的线程都是不一样的。
     * 因为一般 SingleThreadEventExecutor.this.run() 方法都是死循环，导致它会占用绑定的线程。
     *
     * （2）由子类实现抽样方法 SingleThreadEventExecutor.this.run()，
     * 在这个方法中利用死循环，来获取待执行任务队列 taskQueue 中的任务并运行。
     *
     * （3）在 finally 代码中，处理执行器未完成的任务和shutdown 构造函数，
     * 再将执行器的状态从 ST_SHUTTING_DOWN 到 ST_SHUTDOWN，最后到 ST_TERMINATED。
     */
    private void doStartThread() {
        assert thread == null;
        // 这里直接调用 executor.execute 方法，
        // 我们要确保该方法每次都能提供新线程，
        // 否则多个执行器绑定的线程是同一个，是会有问题。
        // 因为一般 SingleThreadEventExecutor.this.run() 方法都是死循环，
        // 这就导致它会占用线程，导致共享线程的其他执行器，是没有办法执行任务的。
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 获取当前线程，赋值给 thread，就是执行器线程
                thread = Thread.currentThread();
                if (interrupted) {
                    // 调用interruptThread()中断当前任务时没有thread值时会设置interrupted标识,现在来调用interrupt方法
                    thread.interrupt();
                }

                boolean success = false;
                // 更新最近一次执行任务时间
                updateLastExecutionTime();
                try {
                    // 这个方法由子类实现，一般情况下，这个方法里面利用死循环，
                    // 来获取待执行任务队列 taskQueue 中的任务并运行。
                    SingleThreadEventExecutor.this.run();
                    success = true;
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    // 采用死循环，使用 CAS 方式，确保执行器状态，变成 ST_SHUTTING_DOWN，或者已经大于 ST_SHUTTING_DOWN
                    for (;;) {
                        int oldState = state;
                        // 当前执行器状态已经是 ST_SHUTTING_DOWN 之后，或者变成 ST_SHUTTING_DOWN 状态
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }

                    // Check if confirmShutdown() was called at the end of the loop.
                    // 检查是否在循环结束时调用了confirmShutdown() 方法。
                    if (success && gracefulShutdownStartTime == 0) {
                        if (logger.isErrorEnabled()) {
                            logger.error("Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                    SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must " +
                                    "be called before run() implementation terminates.");
                        }
                    }

                    try {
                        // Run all remaining tasks and shutdown hooks. At this point the event loop
                        // is in ST_SHUTTING_DOWN state still accepting tasks which is needed for
                        // graceful shutdown with quietPeriod.
                        // 通过死循环和confirmShutdown() 方法，确保运行所有剩余的任务和关闭钩子函数。
                        // 此时，事件循环执行器还处于开始shutdown ST_SHUTTING_DOWN 状态，
                        // 如果使用 shutdownGracefully 方法优雅关闭，仍然可以在安静期 quietPeriod 内接受任务。
                        for (;;) {
                            if (confirmShutdown()) {
                                break;
                            }
                        }

                        // Now we want to make sure no more tasks can be added from this point. This is
                        // achieved by switching the state. Any new tasks beyond this point will be rejected.
                        // 现在我们要确保从这一点开始不能添加更多任务。
                        // 这是通过切换状态来实现的。任何超出此点的新任务都将被拒绝。
                        for (;;) {
                            int oldState = state;
                            if (oldState >= ST_SHUTDOWN || STATE_UPDATER.compareAndSet(
                                    SingleThreadEventExecutor.this, oldState, ST_SHUTDOWN)) {
                                break;
                            }
                        }

                        // We have the final set of tasks in the queue now, no more can be added, run all remaining.
                        // No need to loop here, this is the final pass.
                        confirmShutdown();
                    } finally {
                        try {
                            cleanup();
                        } finally {
                            // Lets remove all FastThreadLocals for the Thread as we are about to terminate and notify
                            // the future. The user may block on the future and once it unblocks the JVM may terminate
                            // and start unloading classes.
                            // See https://github.com/netty/netty/issues/6596.
                            FastThreadLocal.removeAll();

                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.countDown();
                            int numUserTasks = drainTasks();
                            if (numUserTasks > 0 && logger.isWarnEnabled()) {
                                logger.warn("An event executor terminated with " +
                                        "non-empty task queue (" + numUserTasks + ')');
                            }
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }

    final int drainTasks() {
        int numTasks = 0;
        for (;;) {
            Runnable runnable = taskQueue.poll();
            if (runnable == null) {
                break;
            }
            // WAKEUP_TASK should be just discarded as these are added internally.
            // The important bit is that we not have any user tasks left.
            if (WAKEUP_TASK != runnable) {
                numTasks++;
            }
        }
        return numTasks;
    }

    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
