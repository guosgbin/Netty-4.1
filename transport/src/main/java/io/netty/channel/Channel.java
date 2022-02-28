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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.AttributeMap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;


/**
 * A nexus to a network socket or a component which is capable of I/O
 * operations such as read, write, connect, and bind.
 * <p>
 * A channel provides a user:
 * <ul>
 * <li>the current state of the channel (e.g. is it open? is it connected?),</li>
 * <li>the {@linkplain ChannelConfig configuration parameters} of the channel (e.g. receive buffer size),</li>
 * <li>the I/O operations that the channel supports (e.g. read, write, connect, and bind), and</li>
 * <li>the {@link ChannelPipeline} which handles all I/O events and requests
 *     associated with the channel.</li>
 * </ul>
 *
 * <h3>All I/O operations are asynchronous.</h3>
 * <p>
 * All I/O operations in Netty are asynchronous.  It means any I/O calls will
 * return immediately with no guarantee that the requested I/O operation has
 * been completed at the end of the call.  Instead, you will be returned with
 * a {@link ChannelFuture} instance which will notify you when the requested I/O
 * operation has succeeded, failed, or canceled.
 *
 * <h3>Channels are hierarchical</h3>
 * <p>
 * A {@link Channel} can have a {@linkplain #parent() parent} depending on
 * how it was created.  For instance, a {@link SocketChannel}, that was accepted
 * by {@link ServerSocketChannel}, will return the {@link ServerSocketChannel}
 * as its parent on {@link #parent()}.
 * <p>
 * The semantics of the hierarchical structure depends on the transport
 * implementation where the {@link Channel} belongs to.  For example, you could
 * write a new {@link Channel} implementation that creates the sub-channels that
 * share one socket connection, as <a href="http://beepcore.org/">BEEP</a> and
 * <a href="https://en.wikipedia.org/wiki/Secure_Shell">SSH</a> do.
 *
 * <h3>Downcast to access transport-specific operations</h3>
 * <p>
 * Some transports exposes additional operations that is specific to the
 * transport.  Down-cast the {@link Channel} to sub-type to invoke such
 * operations.  For example, with the old I/O datagram transport, multicast
 * join / leave operations are provided by {@link DatagramChannel}.
 *
 * <h3>Release resources</h3>
 * <p>
 * It is important to call {@link #close()} or {@link #close(ChannelPromise)} to release all
 * resources once you are done with the {@link Channel}. This ensures all resources are
 * released in a proper way, i.e. filehandles.
 *
 * 通道Channel 指的是网络套接字socket或能够进行读、写、连接和绑定等I/O操作的组件。
 * 通道Channel为用户提供:
 *（1）通道的当前状态(例如，它是打开的吗?它是连接吗?)
 *（2）通道的配置参数(例如接收缓冲区大小)
 *（3） 通道支持的I/O操作(例如读、写、连接和绑定)
 *（4）通过 ChannelPipeline处理与通道相关的所有I/O事件和请求。
 *
 * Netty中的所有I/O操作都是异步的。
 *
 *      这意味着任何I/O调用将立即返回，而不保证请求的I/O操作在调用结束时已经完成。
 * 相反，您得到一个ChannelFuture实例，该实例将在请求的I/O操作成功、失败或取消时通知您。
 *
 * 释放资源
 *
 *      当通道 Channel 完结的时候，调用close()或close(ChannelPromise) 方法来释放它所持有的资源是非常重要的。
 * 可以确保了所有资源都以适当的方式释放，例如文件句柄。
 *
 *
 * Channel 实现了 ChannelOutboundInvoker 接口说明了 Channel 可以发送出站的IO操作
 */
public interface Channel extends AttributeMap, ChannelOutboundInvoker, Comparable<Channel> {

    /**
     * Returns the globally unique identifier of this {@link Channel}.
     *
     * 返回 Channel 的全局唯一标识
     */
    ChannelId id();

    /**
     * Return the {@link EventLoop} this {@link Channel} was registered to.
     *
     * 返回此 Channel 绑定的 EventLoop 对象
     */
    EventLoop eventLoop();

    /**
     * Returns the parent of this channel.
     * 返回此 Channel 的父 Channel
     * 假如没有父 Channel 则返回 null
     *
     * @return the parent channel.
     *         {@code null} if this channel does not have a parent channel.
     */
    Channel parent();

    /**
     * Returns the configuration of this channel.
     *
     * 返回此 Channel 的配置对象
     */
    ChannelConfig config();

    /**
     * Returns {@code true} if the {@link Channel} is open and may get active later
     *
     * 如果此 Channel 是打开状态或者是待会儿可能会激活 则返回true
     */
    boolean isOpen();

    /**
     * Returns {@code true} if the {@link Channel} is registered with an {@link EventLoop}.
     *
     * 假如 Channel 已经被注册到 EventLoop 则返回 true
     */
    boolean isRegistered();

    /**
     * Return {@code true} if the {@link Channel} is active and so connected.
     *
     * 假如 Channel 是 active 的返回 true
     */
    boolean isActive();

    /**
     * Return the {@link ChannelMetadata} of the {@link Channel} which describe the nature of the {@link Channel}.
     *
     * 返回描述通道性质相关的元数据
     */
    ChannelMetadata metadata();

    /**
     * Returns the local address where this channel is bound to.  The returned
     * {@link SocketAddress} is supposed to be down-cast into more concrete
     * type such as {@link InetSocketAddress} to retrieve the detailed
     * information.
     * 返回此通道绑定到的本地地址。如果此通道未绑定，则返回 null。
     * 返回的 SocketAddress 应该被向下转换为更具体的类型，例如 InetSocketAddress，以检索详细信息。
     *
     * @return the local address of this channel.
     *         {@code null} if this channel is not bound.
     */
    SocketAddress localAddress();

    /**
     * Returns the remote address where this channel is connected to.  The
     * returned {@link SocketAddress} is supposed to be down-cast into more
     * concrete type such as {@link InetSocketAddress} to retrieve the detailed
     * information.
     *
     * 返回此通道连接到的远程地址。
     * 返回的SocketAddress应该被向下转换为更具体的类型，例如 InetSocketAddress，以检索详细信息。
     *
     * @return the remote address of this channel.
     *         {@code null} if this channel is not connected.
     *         If this channel is not connected but it can receive messages
     *         from arbitrary remote addresses (e.g. {@link DatagramChannel},
     *         use {@link DatagramPacket#recipient()} to determine
     *         the origination of the received message as this method will
     *         return {@code null}.
     */
    SocketAddress remoteAddress();

    /**
     * Returns the {@link ChannelFuture} which will be notified when this
     * channel is closed.  This method always returns the same future instance.
     *
     * 返回 ChannelFuture，当此通道关闭时，将通知这个ChannelFuture
     */
    ChannelFuture closeFuture();

    /**
     * Returns {@code true} if and only if the I/O thread will perform the
     * requested write operation immediately.  Any write requests made when
     * this method returns {@code false} are queued until the I/O thread is
     * ready to process the queued write requests.
     *
     * 当且仅当 I/O 线程将立即执行请求的写入操作时返回true
     * 当此方法返回false时发出的任何写入请求都会排队，直到 I/O 线程准备好处理排队的写入请求。
     */
    boolean isWritable();

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     *
     * 获取在isWritable()返回false之前可以写入多少字节
     * 这个数量总是非负的。如果isWritable()为false ，则为 0
     */
    long bytesBeforeUnwritable();

    /**
     * Get how many bytes must be drained from underlying buffers until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     *
     * 获取在 isWritable() 返回 true 之前必须从底层缓冲区排出多少字节。
     * 这个数量总是非负的。如果 isWritable() 是true那么 0。
     */
    long bytesBeforeWritable();

    /**
     * Returns an <em>internal-use-only</em> object that provides unsafe operations.
     *
     * 返回提供不安全操作的仅供内部使用的对象
     */
    Unsafe unsafe();

    /**
     * Return the assigned {@link ChannelPipeline}.
     *
     * 返回此通道绑定的 ChannelPipeline
     */
    ChannelPipeline pipeline();

    /**
     * Return the assigned {@link ByteBufAllocator} which will be used to allocate {@link ByteBuf}s.
     *
     * 获取ByteBuf分配器
     */
    ByteBufAllocator alloc();

    /**
     * 从远端读取数据
     * 默认实现通过 DefaultChannelPipeline 中的内部类 HeadContext 的 read 方法，
     * 调用 Unsafe 的 beginRead() 方法，开始从远端读取消息。
     * @return
     */
    @Override
    Channel read();

    /**
     * 将写缓存区的数据，全部刷新到远程节点
     */
    @Override
    Channel flush();

    /**
     * <em>Unsafe</em> operations that should <em>never</em> be called from user-code. These methods
     * are only provided to implement the actual transport, and must be invoked from an I/O thread except for the
     * following methods:
     * <ul>
     *   <li>{@link #localAddress()}</li>
     *   <li>{@link #remoteAddress()}</li>
     *   <li>{@link #closeForcibly()}</li>
     *   <li>{@link #register(EventLoop, ChannelPromise)}</li>
     *   <li>{@link #deregister(ChannelPromise)}</li>
     *   <li>{@link #voidPromise()}</li>
     * </ul>
     */
    interface Unsafe {

        /**
         * Return the assigned {@link RecvByteBufAllocator.Handle} which will be used to allocate {@link ByteBuf}'s when
         * receiving data.
         *
         * 返回分配的 RecvByteBufAllocator.Handle，用于在接收数据时分配 ByteBuf
         */
        RecvByteBufAllocator.Handle recvBufAllocHandle();

        /**
         * Return the {@link SocketAddress} to which is bound local or
         * {@code null} if none.
         *
         * 返回绑定到本地的 SocketAddress，如果没有则返回 null
         */
        SocketAddress localAddress();

        /**
         * Return the {@link SocketAddress} to which is bound remote or
         * {@code null} if none is bound yet.
         *
         * 返回被绑定为远程的SocketAddress，如果还没有绑定，则返回null
         */
        SocketAddress remoteAddress();

        /**
         * Register the {@link Channel} of the {@link ChannelPromise} and notify
         * the {@link ChannelFuture} once the registration was complete.
         *
         * 将给定的ChannelPromise的对应的通道Channel 注册到给定的事件轮询器 EventLoop；
         * 并在注册完成后通知 ChannelPromise。
         */
        void register(EventLoop eventLoop, ChannelPromise promise);

        /**
         * Bind the {@link SocketAddress} to the {@link Channel} of the {@link ChannelPromise} and notify
         * it once its done.
         *
         * 将本地的SocketAddress绑定到ChannelPromise的Channel，并在绑定完成后通知它
         */
        void bind(SocketAddress localAddress, ChannelPromise promise);

        /**
         * Connect the {@link Channel} of the given {@link ChannelFuture} with the given remote {@link SocketAddress}.
         * If a specific local {@link SocketAddress} should be used it need to be given as argument. Otherwise just
         * pass {@code null} to it.
         *
         * 将给定ChannelPromise的通道Channel与给定的远程SocketAddress进行连接,
         * 同时绑定到localAddress;
         * 一旦连接操作完成，ChannelPromise将得到通知
         *
         * The {@link ChannelPromise} will get notified once the connect operation was complete.
         */
        void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

        /**
         * Disconnect the {@link Channel} of the {@link ChannelFuture} and notify the {@link ChannelPromise} once the
         * operation was complete.
         *
         * 断开给定ChannelPromise的通道Channel的连接，
         * 一旦断开连接操作完成，ChannelPromise将得到通知
         */
        void disconnect(ChannelPromise promise);

        /**
         * Close the {@link Channel} of the {@link ChannelPromise} and notify the {@link ChannelPromise} once the
         * operation was complete.
         *
         * 关闭给定ChannelPromise的通道Channel，
         * 一旦关闭操作完成，ChannelPromise将得到通知
         */
        void close(ChannelPromise promise);

        /**
         * Closes the {@link Channel} immediately without firing any events.  Probably only useful
         * when registration attempt failed.
         */
        void closeForcibly();

        /**
         * Deregister the {@link Channel} of the {@link ChannelPromise} from {@link EventLoop} and notify the
         * {@link ChannelPromise} once the operation was complete.
         *
         *
         * 从 EventLoop 注销 ChannelPromise 的 Channel，并在操作完成后通知 ChannelPromise。
         */
        void deregister(ChannelPromise promise);

        /**
         * Schedules a read operation that fills the inbound buffer of the first {@link ChannelInboundHandler} in the
         * {@link ChannelPipeline}.  If there's already a pending read operation, this method does nothing.
         *
         * 调度一个读操作，以填充ChannelPipeline中第一个ChannelInboundHandler的入站缓冲区。
         */
        void beginRead();

        /**
         * Schedules a write operation.
         * 调度写操作
         */
        void write(Object msg, ChannelPromise promise);

        /**
         * Flush out all write operations scheduled via {@link #write(Object, ChannelPromise)}.
         * 将写缓存区的数据，全部刷新到远程节点
         */
        void flush();

        /**
         * Return a special ChannelPromise which can be reused and passed to the operations in {@link Unsafe}.
         * It will never be notified of a success or error and so is only a placeholder for operations
         * that take a {@link ChannelPromise} as argument but for which you not want to get notified.
         *
         * 返回一个特殊的ChannelPromise，它可以被重用和传递给Channel.Unsafe中的操作。
         * 它永远不会不接收通知(成功和失败的通知都不接收)，所以它只是一个占位符
         * 用于那些接受`ChannelPromise`作为参数但是不想得到通知的方法
         */
        ChannelPromise voidPromise();

        /**
         * Returns the {@link ChannelOutboundBuffer} of the {@link Channel} where the pending write requests are stored.
         *
         * 返回 写缓冲区 ChannelOutboundBuffer 对象，
         * 它缓存了通道Channel 的写数据
         */
        ChannelOutboundBuffer outboundBuffer();
    }
}
