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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    // Channel 通道的 METADATA 数据
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    // 负责刷新发送缓存链表中的数据
    // write的数据没有直接写在Socket中，而是写在了ChannelOutBoundBuffer出站缓存区中
    // 调flush方法会把数据写入Socket并向网络中发送。
    // 当缓存中的数据未发送完成时，需要将此任务添加到EventLoop线程中，等待EventLoop线程的再次发送
    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        // 参数1 服务端channel
        // 餐数2 原生客户端channel
        // 参数3 是初始化事件
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        /**
         * 1 通过 doReadBytes(byteBuf) 方法,从底层NIO 通道中读取数据到输入缓冲区ByteBuf 中。
         * 2 通过 pipeline.fireChannelRead(...) 方法，发送ChannelRead读取事件。
         * 3 通过 allocHandle.continueReading() 判断是否需要继续读取。
         * 4 这次读取完成，调用 pipeline.fireChannelReadComplete() 方法，发送 ChannelReadComplete 读取完成事件。
         */
        @Override
        public final void read() {
            // 获取客户端的配置Config对象
            final ChannelConfig config = config();
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            // 获取客户端的pipeline对象
            final ChannelPipeline pipeline = pipeline();
            // 获取缓冲区分配器，默认是PooledByteBufAllocator
            final ByteBufAllocator allocator = config.getAllocator();
            // 控制读循环和预测下次创建的bytebuf的容量大小
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            // 清空上一次读取的字节数，每次读取时搜重新计算
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    // 参数是缓冲区内存分配器
                    // allocHandle只是预测分配多大的内存
                    byteBuf = allocHandle.allocate(allocator);
                    // doReadBytes(byteBuf) 读取当前Socket读缓冲区的数据到byteBuf对象中
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    // channel底层Socket读缓冲区 已经完全读取完毕会返回0，或者是Channel对端关闭了 返回-1
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            // 此时是 -1
                            readPending = false;
                        }
                        break;
                    }

                    // 更新缓冲区预测分配器 读取消息数量
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    // 因为 TCP 有粘包问题
                    // 向客户端pipeline发送channelRead事件，该pipeline实现了channelRead的Handler就可以进行业务处理了
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;
                } while (allocHandle.continueReading());

                // 读取操作完毕
                allocHandle.readComplete();
                // 触发管道的fireChannelReadComplete事件
                pipeline.fireChannelReadComplete();

                if (close) {
                    // 如果连接对端关闭了，则关闭读操作
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                // 假如读操作完毕，且没有配置自动读，则从选择的Key兴趣集中移除读操作事件
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    /**
     *
     * @param in 写缓冲区 ChannelOutboundBuffer 对象
     * @return
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    /**
     * 写缓冲区 ChannelOutboundBuffer 中的数据分为两类: ByteBuf 和 FileRegion。
     * 调用各自的 doWriteBytes(buf) 和 doWriteFileRegion(region) 方法进行写入数据。这两个方法再上面就有实现。
     * 通过 in.remove() 方法，从写缓冲区ChannelOutboundBuffer中移除当前刷新节点，将下一个节点变成当前节点。
     *
     * @param in
     * @param msg
     * @return
     * @throws Exception
     */
    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                // 没有数据写入，
                // 从写缓冲区ChannelOutboundBuffer中移除当前刷新节点，
                // 获取下一个刷新节点
                in.remove();
                return 0;
            }

            // 将缓存区ByteBuf中的数据写入到底层的NIO通道，返回写入的字节数
            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                // 更新写入的进度
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    /**
     * 1 先得到 writeSpinCount，表示一次写操作，最多能有多少次写操作
     * 是为了防止写缓冲区 ChannelOutboundBuffer 中要写入的数据对象太多了，doWrite(...) 方法执行时间太长，
     * 最多只能写 writeSpinCount 个对象数据，然后通过 incompleteWrite(...) 方法除非下次写循环继续。
     *
     * 2 在循环中通过调用 doWriteInternal(...) 方法进行发送数据。
     * 如果所有消息发送成功了，就清除Write的标记
     *
     * 3 如果写缓冲区数据没有写完，那么调用 incompleteWrite(...) 方法，准备下次写入。
     *
     * @param in
     * @throws Exception
     */
    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        // 写请求自循环次数，默认16次
        int writeSpinCount = config().getWriteSpinCount();
        do {
            // 获取当前Channel的缓存ChannelOutBoundBuffer中的当前待刷新消息
            Object msg = in.current();
            // 所有消息都发送成功了
            if (msg == null) {
                // Wrote all messages.
                // 清除Channel选择Key兴趣事件的OP_WRITE写操作事件
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            // 发送数据
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        /*
         * 当因为缓存区满了而发送失败时，doWriteInternal返回Integer.MAX_VALUE
         * 此时writeSpinCount < 0为true，
         * 当发送16次还未全部发送完，但每次都写成功,writeSpinCount为0
         */
        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            // 是堆外直接返回
            if (buf.isDirect()) {
                return msg;
            }

            // 转换为direct返回
            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            // 没有写完，设置 OP_WRITE 事件
            setOpWrite();
        } else {
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            clearOpWrite();

            // Schedule flush again later so other tasks can be picked up in the meantime
            // 调用任务是，因为防止多路复用器上的其他 Channel饥饿，
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
