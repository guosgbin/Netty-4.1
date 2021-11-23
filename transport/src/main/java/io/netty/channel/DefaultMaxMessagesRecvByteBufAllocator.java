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
package io.netty.channel;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    // 最多读多少次消息
    private volatile int maxMessagesPerRead;
    // 是否没有更多的数据，true 停止读, fasle 继续读
    private volatile boolean respectMaybeMoreData = true;

    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        maxMessagesPerRead(maxMessagesPerRead);
    }

    // 获取每次读取的最大次数
    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    // 设置每次读取的最大次数
    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Determine if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @param respectMaybeMoreData
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     * @return {@code this}.
     */
    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    /**
     * Get if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @return
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     */
    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     */
    // 专注于为continueReading()强制执行每次读取条件下的最大消息数。
    public abstract class MaxMessageHandle implements ExtendedHandle {
        private ChannelConfig config;
        // 每次读循环操作的最大能读取的消息数量， 每到ch内拉一次数据，称为一个消息
        private int maxMessagePerRead;
        // 已经读取的消息数量
        private int totalMessages;
        // 已经读取的消息大小 size
        private int totalBytesRead;
        // 预计读的字节数
        private int attemptedBytesRead;
        // 最后一次读的字节数
        private int lastBytesRead;
        // 是否继续读 true：停止读 false：继续读
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData;

        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override
            public boolean get() {
                // 预估的字节数 == 最后一次的读的字节数
                // 是否把缓冲区内可写的空间全填满
                // true：表示最后一次读取的数据量 和 预估的数据量 一样大，说明ch内可能还有剩余数据没有读取完毕，还需要继续读取
                // false：1.预估的数据量产生一个 ByteBuf > 剩余数据量 2. ch是close状态了，lastBytesRead是-1，表示不需要继续读循环了
                return attemptedBytesRead == lastBytesRead;
            }
        };

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         */
        @Override
        public void reset(ChannelConfig config) {
            this.config = config;
            // 重新设置 读循环操作 最大可读消息量，默认情况下是16，服务端和客户端都是16
            maxMessagePerRead = maxMessagesPerRead();
            // 统计字段归零
            totalMessages = totalBytesRead = 0;
        }

        /**
         * @param alloc 真正分配内存的 缓冲区分配器
         * @return
         */
        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            // guess()根据度循环过程中的上下文 预估一个适合本次读大小的值
            // alloc.ioBuffer 真正分配缓冲区的对象
            return alloc.ioBuffer(guess());
        }

        /**
         * 增加读消息的数量
         * @param amt
         */
        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }

        /**
         * 保存上次读取的字节数
         */
        @Override
        public void lastBytesRead(int bytes) {
            lastBytesRead = bytes;
            if (bytes > 0) {
                totalBytesRead += bytes;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        @Override
        public boolean continueReading() {
            return continueReading(defaultMaybeMoreSupplier);
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            // 控制读循环是否继续
            // 1.config.isAutoRead() 默认是true
            // 2.maybeMoreDataSupplier.get()   true：表示最后一次读取的数据量 和 预估的数据量 一样大，说明ch内可能还有剩余数据没有读取完毕，还需要继续读取
            // 3.totalMessages < maxMessagePerRead  一次unsafe.read 最多能从ch中读取16次数据，不能超出16
            // 4.totalBytesRead > 0
            //      客户端一般是true
            //      服务端 这里的值是fasle，每次unsafe.read 只进行一次读循环
            return config.isAutoRead() &&
                   (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&
                   totalMessages < maxMessagePerRead &&
                   totalBytesRead > 0;
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
