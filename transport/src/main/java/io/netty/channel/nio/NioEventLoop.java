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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 * SingleThreadEventLoop的一个实现
 * 将Channel注册到Selector并且在事件循环中对这些进行多路复用。
 *
 * netty 是如何通过一个事件轮询器管理多个嵌套字 Socket 的通道 channel。
 * 又是如何即处理通道的 channel 的 IO 事件，以及添加事件轮询器上任务的。
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    // 每当256个Channel从Selector上移除时，就会去标记needsToSelectAgain为true
    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    // 超过指定次数就会重建 Selector，为了修复NIO的空轮询bug
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.java.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     * NIO的Selector
     */
    private Selector selector;
    // JDK原生的
    private Selector unwrappedSelector;
    // 当前NioEventLoop的Selector就绪事件的集合
    private SelectedSelectionKeySet selectedKeys;

    // 选择器提供器
    private final SelectorProvider provider;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    // nextWakeupNanos is:
    //    AWAKE            表示 已经是唤醒状态，为了阻止不必要的选择器唤醒
    //    NONE             表示 Schedule 调度任务队列中没有 到时间 要执行的调度任务
    //    other value T    表示 Schedule 调度任务队列中有 到时间 要执行的调度任务,需要在 T 时间执行
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    // 选择器提供策略
    private final SelectStrategy selectStrategy;

    // I/O 事件的执行时间和本地队列任务执行时间的占比
    private volatile int ioRatio = 50;
    private int cancelledKeys;
    // 每当256个Channel从Selector上移除时，就会去标记needsToSelectAgain为true
    private boolean needsToSelectAgain;

    /**
     *
     * @param parent 当前NioEventLoop所属的NioEventLoopGroup
     * @param executor 线程执行器
     * @param selectorProvider 选择器提供器
     * @param strategy 选择策略
     * @param rejectedExecutionHandler 拒绝策略
     * @param taskQueueFactory
     * @param tailTaskQueueFactory
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory taskQueueFactory, EventLoopTaskQueueFactory tailTaskQueueFactory) {
        // 参数一：当前NioEventLoop所属的NioEventLoopGroup
        // 参数二：ThreadPerTaskExecutor, 是在Group中创建的
        // 参数三：
        // 参数四：最终返回的是一个队列，最大程度是Integer.MAX_VALUE，最小是16
        // 参数五：大部分用不到这个queue
        // 参数六：线程池拒绝策略
        super(parent, executor, false, newTaskQueue(taskQueueFactory), newTaskQueue(tailTaskQueueFactory),
                rejectedExecutionHandler);
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        // 创建包装后的Selector和未包装的Selector实例
        // 也就是每个NioEventLoop都持有有一个Selector实例
        final SelectorTuple selectorTuple = openSelector();
        this.selector = selectorTuple.selector;
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            // 获取 JDK 原生的选择器对象
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEY_SET_OPTIMIZATION) {
            // 配置的是不优化选择器，直接返回
            return new SelectorTuple(unwrappedSelector);
        }

        // 使用反射机制，获取JDK底层的Selector的Class对象
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        // 当前NioEventLoop的Selector就绪事件的集合
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 通过反射获取selectedKeys和publicSelectedKeys两个字段
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset = PlatformDependent.objectFieldOffset(publicSelectedKeysField);
                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            // 将上面获取的两个属性重新赋值为Netty的SelectedSelectionKeySet
                            PlatformDependent.putObject(unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    // 将上面获取的两个属性重新赋值为Netty的SelectedSelectionKeySet
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            // 发生了异常
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    public static void main(String[] args) {
        NioEventLoopGroup group = new NioEventLoopGroup();
        System.out.println(group);
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     *
     * 将任意SelectableChannel注册到此事件循环的Selector ，不一定由 Netty 创建。
     * 一旦指定的SelectableChannel被注册，当SelectableChannel准备好时，指定的task将被这个事件循环执行
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            // 在事件轮询器线程中，直接调用 register0 方法注册
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                // 调用 submit  方法，确保在轮询器线程中注册
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                // 即使被中断了，我们也会调度它，所以只需将线程标记为中断。
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            // 将通道 ch 注册到选择器 unwrappedSelector 中
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    /**
     * 1.创建一个新的Selector
     * 2.将原来的Selector中注册的事件全部取消
     * 3.将可用事件重新注册到新的Selector并激活
     */
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            // 开启新的Selector
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        // 遍历旧的Selector上的key
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                // 取消旧的Selector上触发的事件
                int interestOps = key.interestOps();
                key.cancel();
                // 把Channel注册到新的Selector上
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            // 关闭旧的Selector
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * 死循环 不断的轮询 SelectionKey
     *
     * 首先使用选择策略 SelectStrategy 根据 hasTasks() 是否有任务
     *   1.有待执行的任务，先直接调用 selectNow() 方法，获取当前准备好IO事件的通道channel ，然后处理 IO 事件和待执行任务。
     *   2.没有待执行任务，那么就需要阻塞等待准备好IO事件的通道；
     *   先获取下一个计划任务的截止时间 curDeadlineNanos，调用 select(curDeadlineNanos) 方法。
     *
     * 开始处理IO事件和ScheduledTask任务
     * 属性 ioRatio，它表示事件循环中，处理IO 事件所占的时间比例。这个值越大，处理非IO 事件(待执行任务)的时间就越少；
     *   1.ioRatio == 100，如果strategy > 0，则说明当前NIoEventLoop的Selector上有就绪事件，
     *   通过processSelectedKeys() 处理IO事件，最后调用 runAllTasks() 运行全部的待执行任务。
     *
     *   2.前置条件 ioRatio != 100,如果strategy > 0，则说明当前NIoEventLoop的Selector上有就绪事件，
     *   通过processSelectedKeys() 处理IO事件。
     *   计算处理IO事件的花费的时间 ioTime，根据这个时间，计算出执行非IO事件(即待执行任务)最多花费的时间
     *   ioTime * (100 - ioRatio) / ioRatio，这样就可以控制运行任务的时间了。
     *
     *   3.没有 IO 事件(strategy == 0)，调用 runAllTasks(0) 方法，运行最小数量（64）的任务。
     *
     *   4.最后需要考虑，如果这次被唤醒，即没有任务运行，也没有IO事件处理，那么就有可能是 JDK 的 epoll 的空轮询 BUG;需要重新注册选择器。
     */
    @Override
    protected void run() {
        // epoll bug的一个特征计数变量
        int selectCnt = 0;
        for (;;) {
            try {
                // 1. >= 0 表示Selector的返回值，注册在Selector上就绪事件的个数
                // 2. < 0 状态常量 CONTINUE BUSY_WAIT SELECT
                int strategy;
                try {
                    // 根据当前NioEventLoop是否有任务，来判断
                    // 1.有任务，调用Selector的selectNow()方法，返回就绪事件的个数
                    // 2.没有任务，直接返回SELECT 也就是-1
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                    case SelectStrategy.CONTINUE:
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        // 返回下一个计划任务准备运行的截止时间纳秒值
                        // 返回-1表示 NioEventLoop中没有需要周期性调度的任务
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        if (curDeadlineNanos == -1L) {
                            // 将 curDeadlineNanos 设置为 Long.MAX_VALUE，
                            curDeadlineNanos = NONE; // nothing on the calendar
                        }
                        // 设置 超时等待时间
                        nextWakeupNanos.set(curDeadlineNanos);
                        try {
                            // 条件成立：表示没有本地普通任务 需要执行
                            if (!hasTasks()) {
                                // curDeadlineNanos
                                // 1. NONE
                                // 2. 表示周期性任务需要执行的 截止时间
                                strategy = select(curDeadlineNanos);
                            }
                        } finally {
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    // 出现I/O异常时重新构建Selector
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                // 走到此处，strategy就表示 channel上就绪事件的个数
                // 要么就是有I/O事件，要么就是有scheduledTask，或者是JDK的 epoll 的空轮询 BUG

                selectCnt++;
                cancelledKeys = 0;
                needsToSelectAgain = false;

                // 线程处理IO事件的时间占比，默认是50%
                final int ioRatio = this.ioRatio;
                // 表示本轮线程有没有处理过本地任务
                boolean ranTasks;
                // 条件成立表示IO优先，IO处理完之后，再处理本地任务
                if (ioRatio == 100) {
                    try {
                        // 条件成立：说明当前NIoEventLoop的Selector上有就绪事件
                        if (strategy > 0) {
                            // 处理IO事件
                            processSelectedKeys();
                        }
                    } finally {
                        // Ensure we always run tasks.
                        // 确保运行了所有待执行任务，包括ScheduledTask任务
                        // 执行本地任务队列的任务
                        ranTasks = runAllTasks();
                    }
                }
                // 条件成立，说明当前NIoEventLoop的Selector上有就绪事件
                else if (strategy > 0) {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // IO事件处理总耗时
                        final long ioTime = System.nanoTime() - ioStartTime;
                        // 计算执行本地队列任务的最大时间，根据ioRatio，有可能遗留一部分任务等待下次执行
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
                // 条件成立，说明当前NioEventLoop上没有就绪事件，只处理本地任务就行了
                // 也就是说没有IO事件了
                else {
                    // 最多只能执行64个任务
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }

                if (ranTasks || strategy > 0) { // 要么有任务运行，要么有 IO 事件处理
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    // 正常流程进到这里面，NioEventLoop线程从Selector唤醒后工作，是因为有IO事件
                    selectCnt = 0;
                }
                // 处理nio的bug
                else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case)
                    // 即没有任务运行，也没有IO 事件处理，就有可能是 JDK 的 epoll 的空轮询 BUG
                    // 调用 unexpectedSelectorWakeup(selectCnt) 方法处理。
                    // 可能会重新建立 Select
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    if (isShuttingDown()) {
                        // 如果事件轮询器开始 shutdown，就要关闭 IO 资源
                        closeAll();
                        if (confirmShutdown()) {
                            return;
                        }
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }

    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        // 轮训超过默认的512次 就认为是bug状态了
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            rebuildSelector();
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        // 避免可能出现的连续的直接故障导致CPU占用过多。
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        // 判断优化后的selectedKeys是否为空
        if (selectedKeys != null) {
            // 优化处理
            processSelectedKeysOptimized();
        } else {
            // 原始处理
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // 没有 IO 事件了，跳出循环
            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                // 如果needsToSelectAgain == true， 需要重新获取IO事件，
                selectAgain();
                // 再次获取 IO 事件的 selectedKeys
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    // 没有新的 IO 事件，就直接返回。
                    break;
                } else {
                    // 重新获取新的迭代器, 避免ConcurrentModificationException
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            // 就绪事件
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            // 先将selectedKeys.keys[i]置空，快速GC，不需要等到调用其重置再去回收，因为key的附件比较大，很容易造成内存泄露
            selectedKeys.keys[i] = null;

            // 附件，这里会拿到注册时向Selector提供的Channel对象
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                // 处理IO事件，根据key的就绪事件触发对应的事件方法
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // 判断是否应该再次轮询，每当256个channel从selector上移除时，就标记needsToSelectAgain为true
            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 处理通道 AbstractNioChannel 的IO事件
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // NioServerSocketChannel -> NioMessageUnsafe
        // NioSocketChannel -> NioByteUnsafe
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                // 如果通道实现因为没有事件循环器而抛出异常，则忽略此异常，
                // 因为我们只是试图确定ch是否注册到该事件循环器，从而有权关闭ch。
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // 只有当ch仍然注册到这个EventLoop时才关闭ch。
            // ch可能已经从事件循环中注销，因此SelectionKey可以作为注销过程的一部分被取消，
            // 但通道仍然健康，不应该关闭。
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            // 获取 IO 事件类型
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // 首先判断是不是连接的IO事件 OP_CONNECT
            // 在尝试触发read(…)或write(…)之前，
            // 我们首先需要调用finishConnect()，
            // 否则NIO JDK通道实现可能抛出 NotYetConnectedException 异常。
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                // 删除OP_CONNECT，否则Selector.select(..)将始终返回而不阻塞
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                // 连接完成则调用finishiConnect操作
                unsafe.finishConnect();
            }

            // 首先处理写事件 OP_WRITE，因为我们可以写一些队列缓冲区，从而释放内存。
            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 调用forceFlush，即使没有东西可写，它也会清除OP_WRITE
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // 最后处理读事件
            // 还要检查 readOps 是否为0，以解决可能导致旋转循环的JDK错误
            // (readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0  正常逻辑 channel有read或者accept事件
            // readyOps == 0 是解决NIO的bug
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            // key失效则close这个channel
            unsafe.close(unsafe.voidPromise());
        }
    }

    /**
     * 处理 NioTask 任务，
     */
    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            default:
                 break;
            }
        }
    }

    /**
     * 把原本注册再 selector 上的所有 Channel 都关闭
     */
    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    /**
     * 返回注册在该 Selector 上的已经准备好进行I/O操作的通道 channel 的数量。
     * 如果还没有准备好的通道 channel ，那么直接返回 0，不会阻塞当前线程。
     */
    int selectNow() throws IOException {
        return selector.selectNow();
    }

    /**
     * 阻塞获取
     *
     * @param deadlineNanos
     * @return
     * @throws IOException
     */
    private int select(long deadlineNanos) throws IOException {
        // 当前没有周期性任务，直接阻塞当前调用线程，直到就绪事件发生，返回就绪事件个数

        // 如果截止时间 deadlineNanos 是NONE(无限大)
        // 那么就使用 selector.select() 方法，不设置超时，
        // 一直阻塞等待，直到有注册在该 selector 上通道 channel 已经准备好进行I/O操作，
        // 才停止阻塞，返回准备好I/O操作 channel 的数量。
        if (deadlineNanos == NONE) {
            return selector.select();
        }
        // Timeout will only be 0 if deadline is within 5 microsecs
        // 计算调用 selector.select(timeoutMillis) 的超时阻塞等待时间。
        // 如果截止时间在5微秒内，超时时间将视为0
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    /**
     * 重新获取 IO 事件，即再次调用 selector.selectNow()， 不阻塞线程
     */
    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
