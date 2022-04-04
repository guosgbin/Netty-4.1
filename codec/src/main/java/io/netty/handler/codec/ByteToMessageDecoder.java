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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Integer.MAX_VALUE;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 *
 *
 * DelimiterBasedFrameDecoder 分隔符
 * FixedLengthFrameDecoder 固定长度
 * LengthFieldBasedFrameDecoder
 * LineBasedFrameDecoder 行位
 *
 * 如果需要自定义帧解码器，那么在使用 {@link ByteToMessageDecoder} 实现解码器时需要小心。
 * 通过检查 {@link ByteBuf#readableBytes()}，确保缓冲区中有足够的字节用于完整的帧。
 * 如果没有足够的字节用于完整的帧，则在不修改读取器索引的情况下返回以允许更多字节到达
 *
 * ByteToMessageDecoder 的子类不能被 @Sharable 注解修饰
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     */
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            if (!cumulation.isReadable() && in.isContiguous()) {
                // If cumulation is empty and input buffer is contiguous, use it directly
                // 释放掉内存
                cumulation.release();
                return in;
            }
            try {
                // 获取此次读取到的消息的可读字节大小
                final int required = in.readableBytes();
                // 假如 可读字节大小 > 堆积区的可写字节大小
                // 说明堆积区小了，需要扩容
                if (required > cumulation.maxWritableBytes() ||
                        (required > cumulation.maxFastWritableBytes() && cumulation.refCnt() > 1) ||
                        cumulation.isReadOnly()) {
                    // Expand cumulation (by replacing it) under the following conditions:
                    // - cumulation cannot be resized to accommodate the additional data
                    // - cumulation can be expanded with a reallocation operation to accommodate but the buffer is
                    //   assumed to be shared (e.g. refCnt() > 1) and the reallocation may not be safe.
                    // 返回扩容后的堆积区，新的堆积区包含原堆积区的所有未处理的数据和 in 内部的所有数据
                    return expandCumulation(alloc, cumulation, in);
                }
                // 执行到此处，说明当前堆积区的容量足够

                // 写数据到 cumulation
                cumulation.writeBytes(in, in.readerIndex(), required);
                // 更新读指针
                in.readerIndex(in.writerIndex());
                return cumulation;
            } finally {
                // We must release in in all cases as otherwise it may produce a leak if writeBytes(...) throw
                // for whatever release (for example because of OutOfMemoryError)
                // 释放内存
                in.release();
            }
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            if (!cumulation.isReadable()) {
                cumulation.release();
                return in;
            }
            CompositeByteBuf composite = null;
            try {
                if (cumulation instanceof CompositeByteBuf && cumulation.refCnt() == 1) {
                    composite = (CompositeByteBuf) cumulation;
                    // Writer index must equal capacity if we are going to "write"
                    // new components to the end
                    if (composite.writerIndex() != composite.capacity()) {
                        composite.capacity(composite.writerIndex());
                    }
                } else {
                    composite = alloc.compositeBuffer(Integer.MAX_VALUE).addFlattenedComponents(true, cumulation);
                }
                composite.addFlattenedComponents(true, in);
                in = null;
                return composite;
            } finally {
                if (in != null) {
                    // We must release if the ownership was not transferred as otherwise it may produce a leak
                    in.release();
                    // Also release any new buffer allocated if we're not returning it
                    if (composite != null && composite != cumulation) {
                        composite.release();
                    }
                }
            }
        }
    };

    // 初始状态
    private static final byte STATE_INIT = 0;
    // 调用子类实现的 decode 方法时期状态
    private static final byte STATE_CALLING_CHILD_DECODE = 1;
    // 当 decoder 从 pipeline 移除出去之后状态会修改为 removed_pending 状态
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;

    // 堆积区 ByteBuf，消息从入站后中回去到时存到这里
    ByteBuf cumulation;
    // 接收到单次消息后 通过 cumulator 合并到堆积区内
    private Cumulator cumulator = MERGE_CUMULATOR;
    // 默认是 false，如果是 true 每次 ChannelRead 只解码一个 frame
    private boolean singleDecode;
    // 默认是 false，表示 cumulation 值是 null，否则 cumulation 不为 null
    // 当 cumulation 内部的数据完全处理完毕之后，Decoder 会把 cumulation 占用的堆外内存释放，并将 cumulation 字段置为 null
    private boolean first;

    /**
     * This flag is used to determine if we need to call {@link ChannelHandlerContext#read()} to consume more data
     * when {@link ChannelConfig#isAutoRead()} is {@code false}.
     */
    private boolean firedChannelRead;

    /**
     * A bitmask where the bits are defined as
     * <ul>
     *     <li>{@link #STATE_INIT}</li>
     *     <li>{@link #STATE_CALLING_CHILD_DECODE}</li>
     *     <li>{@link #STATE_HANDLER_REMOVED_PENDING}</li>
     * </ul>
     */
    // 解码器的状态
    private byte decodeState = STATE_INIT;
    // 清理 cumulation 已读空间的阈值，当 cumulation 的 readIndex 比较大时，cumulation 可写的空间就会变小
    // 用过整理的方法可以调整 cumulation 的 readerIndex 和 writerIndex 让其复用空闲的内存
    private int discardAfterReads = 16;
    // 表示当前 cumulation 已经累积进去多少条数据
    private int numReads;

    protected ByteToMessageDecoder() {
        ensureNotSharable();
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        this.cumulator = ObjectUtil.checkNotNull(cumulator, "cumulator");
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return;
        }
        ByteBuf buf = cumulation;
        if (buf != null) {
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            cumulation = null;
            numReads = 0;
            int readable = buf.readableBytes();
            if (readable > 0) {
                ctx.fireChannelRead(buf);
                ctx.fireChannelReadComplete();
            } else {
                buf.release();
            }
        }
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            // 池化管理 其实就是一个 List
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                first = cumulation == null;
                // 将单次消息合并到累积区中
                // 参数 1：内存池
                // 参数 2：堆积区
                // 参数 3：此次读取到的消息
                cumulation = cumulator.cumulate(ctx.alloc(),
                        first ? Unpooled.EMPTY_BUFFER : cumulation, (ByteBuf) msg);
                callDecode(ctx, cumulation, out);
                // 走到此处，情况
                // 1 cumulation 堆积区内的数据全部解码成 frame，并且已经发送到decoder 的后面额 handler 去处理了（out 内可能有未发送的 frame）
                // 2 cumulation 有半包情况
            } catch (DecoderException e) {
                throw e;
            } catch (Exception e) {
                throw new DecoderException(e);
            } finally {
                try {
                    if (cumulation != null && !cumulation.isReadable()) {
                        // 条件成立：说明 cumulation 内部的数据全部处理完成了
                        numReads = 0;
                        // 将堆积区的内存释放
                        cumulation.release();
                        // 堆积区字段置为 null，下次再有 channelRead 事件进来，再次重建堆积区
                        cumulation = null;
                    } else if (++numReads >= discardAfterReads) {
                        // 走到此处的前提是，堆积区中有半包，
                        // 且累加的次数大于等于阈值 16
                        //

                        // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                        // See https://github.com/netty/netty/issues/4275
                        numReads = 0;
                        // 调整堆积区 ByteBuf 的 ReaderIndex 和 writeIndex 指针，重复利用空闲内存空间
                        discardSomeReadBytes();
                    }

                    int size = out.size();
                    firedChannelRead |= out.insertSinceRecycled();
                    // 将剩余未发送的 frame 发送到后面的 Handler
                    fireChannelRead(ctx, out, size);
                } finally {
                    // 归还对象池
                    out.recycle();
                }
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i ++) {
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        numReads = 0;
        discardSomeReadBytes();
        if (!firedChannelRead && !ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
        firedChannelRead = false;
        ctx.fireChannelReadComplete();
    }

    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                int size = out.size();
                fireChannelRead(ctx, out, size);
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            callDecode(ctx, cumulation, out);
            // If callDecode(...) removed the handle from the pipeline we should not call decodeLast(...) as this would
            // be unexpected.
            if (!ctx.isRemoved()) {
                // Use Unpooled.EMPTY_BUFFER if cumulation become null after calling callDecode(...).
                // See https://github.com/netty/netty/issues/10802.
                ByteBuf buffer = cumulation == null ? Unpooled.EMPTY_BUFFER : cumulation;
                decodeLast(ctx, buffer, out);
            }
        } else {
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data   堆积区
     * @param out           the {@link List} to which decoded messages should be added 已解码的数据集合
     */
    protected void  callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            // 条件是：堆积区中已经没有待处理的数据了
            while (in.isReadable()) {
                // 当调用传过来的 out 的 list 的 size 是 0
                // 在 while 循环中解码一帧数据就会添加到 out 的 list 中
                final int outSize = out.size();

                if (outSize > 0) {
                    // 解码出来的数据向后传递
                    fireChannelRead(ctx, out, outSize);
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    if (ctx.isRemoved()) {
                        break;
                    }
                }

                // 获取堆积区待处理数据量大小
                int oldInputLength = in.readableBytes();
                // 调用子类的 decode 方法，子类将 in 的数据解码，存放到 out 的 list 中
                decodeRemovalReentryProtection(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                if (ctx.isRemoved()) {
                    break;
                }

                // 条件成立：out.size() == 0
                if (out.isEmpty()) {
                    if (oldInputLength == in.readableBytes()) {
                        // 成立：说明 in 堆积去内的数据是半包数据
                        break;
                    } else {
                        // 丢弃
                        continue;
                    }
                }

                // 走到此处 说明已经解码出一条数据了，放到 out 的 list 里了

                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }

                if (isSingleDecode()) {
                    // 直解码一帧数据
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     *
     * in 传入数据， out 是用来添加解码消息的 List。
     * 这个方法的调用将会重复进行，直到确定没有新的元素被添加到该 List，或者该 ByteBuf 中没有更多的可读取的字节时为止。
     * 然后，如果该 List 不为空，那么它的内容将会被传递给 ChannelPipeline 中的下一个 ChannelInboundHandler。
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        decodeState = STATE_CALLING_CHILD_DECODE;
        try {
            decode(ctx, in, out);
        } finally {
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
            decodeState = STATE_INIT;
            if (removePending) {
                fireChannelRead(ctx, out, out.size());
                out.clear();
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     *
     * 当 Channel 的状态变为非活动时，这个方法将会被调用一次，
     * 默认实现只是简单地调用了decode()方法，当然子类可以重写该方法。
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }

    /**
     * 扩容
     *
     * @param alloc
     * @param oldCumulation 原堆积区
     * @param in 此次读取的数据
     * @return
     */
    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf oldCumulation, ByteBuf in) {
        // 原堆积区的未处理的数据量大小
        int oldBytes = oldCumulation.readableBytes();
        // 本次待处理的数据量
        int newBytes = in.readableBytes();
        int totalBytes = oldBytes + newBytes;
        // 获取一个新的 ByteBuf
        ByteBuf newCumulation = alloc.buffer(alloc.calculateNewCapacity(totalBytes, MAX_VALUE));
        // 待释放的内存，这个是为了防止 下面的操作出现异常
        // 假如出现异常，释放的是 newCumulation
        ByteBuf toRelease = newCumulation;
        try {
            // This avoids redundant checks and stack depth compared to calling writeBytes(...)
            // 将原堆积区的数据和此次读取到的数据写到 新的堆积区中
            newCumulation.setBytes(0, oldCumulation, oldCumulation.readerIndex(), oldBytes)
                .setBytes(oldBytes, in, in.readerIndex(), newBytes)
                    // 更新写索引
                .writerIndex(totalBytes);
            // 表示此次读取的消息已经读取完了
            in.readerIndex(in.writerIndex());
            toRelease = oldCumulation;
            return newCumulation;
        } finally {
            // 释放内存
            toRelease.release();
        }
    }

    /**
     * Cumulate {@link ByteBuf}s.
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}
