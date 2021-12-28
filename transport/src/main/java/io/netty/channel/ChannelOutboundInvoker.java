/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FutureListener;

import java.net.ConnectException;
import java.net.SocketAddress;

/**
 * 用来传递出站 IO 操作
 *
 * ChannelOutboundInvoker 的子接口管道 ChannelPipeline , 通过ChannelOutboundInvoker 的方法，
 * 在整个出站处理器ChannelOutboundHandler 链表传递出站I/O操作。
 *
 * ChannelOutboundInvoker 的子接口上下文 ChannelHandlerContext ,
 * 通过ChannelOutboundInvoker 的方法，向 ChannelOutboundHandler 链表上游传递出站I/O操作。
 *
 * ChannelOutboundInvoker 的子接口通道 Channel, 它的ChannelOutboundInvoker 方法实现，
 * 就是直接调用通道 Channel 拥有的管道 ChannelPipeline对应方法。
 */
public interface ChannelOutboundInvoker {

    /**
     * Request to bind to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     * <p>
     * 将通道绑定到给定的 SocketAddress 地址，并在操作完成后通知 ChannelFuture
     *
     * 这个方法会调用此通道 Channel 对应的管道 ChannelPipeline 中
     * 下一个出站处理器 ChannelOutboundHandler 的 bind(ChannelHandlerContext, SocketAddress, ChannelPromise) 方法。
     *
     * This will result in having the
     * {@link ChannelOutboundHandler#bind(ChannelHandlerContext, SocketAddress, ChannelPromise)} method
     * called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture bind(SocketAddress localAddress);

    /**
     * Request to connect to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     *
     * 将通道连接到给定的 remoteAddress 远程地址，同时绑定到localAddress，并在操作完成后通知 ChannelFuture
     *
     * 如果连接超时导致连接失败，ChannelFuture将使用ConnectTimeoutException 异常。
     * 如果因为连接被拒绝而失败，则会使用ConnectException 异常。
     *
     * 这个方法会调用此通道 Channel 对应的管道 ChannelPipeline 中
     * 下一个出站处理器 ChannelOutboundHandler 的
     * connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise) 方法。
     *
     * <p>
     * If the connection fails because of a connection timeout, the {@link ChannelFuture} will get failed with
     * a {@link ConnectTimeoutException}. If it fails because of connection refused a {@link ConnectException}
     * will be used.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture connect(SocketAddress remoteAddress);

    /**
     * Request to connect to the given {@link SocketAddress} while bind to the localAddress and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     * <p>
     *
     * 请求连接到给定的SocketAddress，同时绑定到localAddress，
     * 并在操作完成后通知ChannelFuture(可能是操作成功，也可能是发生了错误)。
     *
     * 这个方法会调用
     * 此通道 Channel 对应的管道 ChannelPipeline 中
     * 下一个出站处理器 ChannelOutboundHandler 的 connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise) 方法。
     *
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress);

    /**
     * Request to disconnect from the remote peer and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of an error.
     * <p>
     *
     * 请求断开与远程对端的连接，并在操作完成后通知ChannelFuture(可能是操作成功，也可能是发生了错误)。
     *
     * This will result in having the
     * {@link ChannelOutboundHandler#disconnect(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture disconnect();

    /**
     * Request to close the {@link Channel} and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of
     * an error.
     *
     * 请求关闭 Channel，并在操作完成后通知ChannelFuture ，无论是因为操作成功还是因为错误。
     *
     * 关闭后无法再次使用。
     *
     * 这会调用包含在 Channel 的 ChannelPipeline 中的下一个 ChannelOutboundHandler
     * 的ChannelOutboundHandler.close(ChannelHandlerContext, ChannelPromise)方法。
     *
     * After it is closed it is not possible to reuse it again.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture close();

    /**
     * Request to deregister from the previous assigned {@link EventExecutor} and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     * <p>
     *
     * 请求从先前分配的 EventExecutor 注销并在操作完成后通知 ChannelFuture ，可能操作成功，也可能错误。
     *
     * 这个方法会调用
     * 此通道 Channel 对应的管道 ChannelPipeline 中
     * 下一个出站处理器 ChannelOutboundHandler 的 deregister(ChannelHandlerContext, ChannelPromise) 方法。
     *
     * This will result in having the
     * {@link ChannelOutboundHandler#deregister(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     */
    ChannelFuture deregister();

    /**
     * Request to bind to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#bind(ChannelHandlerContext, SocketAddress, ChannelPromise)} method
     * called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise);

    /**
     * Request to connect to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     *
     * The given {@link ChannelFuture} will be notified.
     *
     * <p>
     * If the connection fails because of a connection timeout, the {@link ChannelFuture} will get failed with
     * a {@link ConnectTimeoutException}. If it fails because of connection refused a {@link ConnectException}
     * will be used.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise);

    /**
     * Request to connect to the given {@link SocketAddress} while bind to the localAddress and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     *
     * The given {@link ChannelPromise} will be notified and also returned.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

    /**
     * Request to disconnect from the remote peer and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#disconnect(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture disconnect(ChannelPromise promise);

    /**
     * Request to close the {@link Channel} and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of
     * an error.
     *
     * After it is closed it is not possible to reuse it again.
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture close(ChannelPromise promise);

    /**
     * Request to deregister from the previous assigned {@link EventExecutor} and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#deregister(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture deregister(ChannelPromise promise);

    /**
     * Request to Read data from the {@link Channel} into the first inbound buffer, triggers an
     * {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)} event if data was
     * read, and triggers a
     * {@link ChannelInboundHandler#channelReadComplete(ChannelHandlerContext) channelReadComplete} event so the
     * handler can decide to continue reading.  If there's a pending read operation already, this method does nothing.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#read(ChannelHandlerContext)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     * 请求从Channel读取数据到第一个入站缓冲区，如果数据被读取，
     * 将触发ChannelInboundHandler 的 channelRead(ChannelHandlerContext, Object)事件，
     * 并触发ChannelInboundHandler 的 channelReadComplete事件，以便处理程序可以决定继续读取。
     * 如果已经有一个挂起的读操作，这个方法什么也不做。
     */
    ChannelOutboundInvoker read();

    /**
     * Request to write a message via this {@link ChannelHandlerContext} through the {@link ChannelPipeline}.
     * This method will not request to actual flush, so be sure to call {@link #flush()}
     * once you want to request to flush all pending data to the actual transport.
     *
     * 请求通过这个 ChannelHandlerContext 通过ChannelPipeline写入消息。
     * 此方法不会立刻刷新，因此当您希望请求将所有挂起的数据刷新到实际传输时，请确保调用flush()。
     */
    ChannelFuture write(Object msg);

    /**
     * Request to write a message via this {@link ChannelHandlerContext} through the {@link ChannelPipeline}.
     * This method will not request to actual flush, so be sure to call {@link #flush()}
     * once you want to request to flush all pending data to the actual transport.
     */
    ChannelFuture write(Object msg, ChannelPromise promise);

    /**
     * Request to flush all pending messages via this ChannelOutboundInvoker.
     * 请求通过此 ChannelOutboundInvoker 刷新所有挂起的消息。
     */
    ChannelOutboundInvoker flush();

    /**
     * Shortcut for call {@link #write(Object, ChannelPromise)} and {@link #flush()}.
     */
    ChannelFuture writeAndFlush(Object msg, ChannelPromise promise);

    /**
     * Shortcut for call {@link #write(Object)} and {@link #flush()}.
     */
    ChannelFuture writeAndFlush(Object msg);

    /**
     * Return a new {@link ChannelPromise}.
     */
    ChannelPromise newPromise();

    /**
     * Return an new {@link ChannelProgressivePromise}
     */
    ChannelProgressivePromise newProgressivePromise();

    /**
     * Create a new {@link ChannelFuture} which is marked as succeeded already. So {@link ChannelFuture#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     *
     * 创建一个新的ChannelFuture ，它已经被标记为成功。
     * 所以ChannelFuture.isSuccess()将返回true
     * 所有添加到它的FutureListener都会被直接通知。 此外，每次调用阻塞方法都会返回而不会阻塞。
     */
    ChannelFuture newSucceededFuture();

    /**
     * Create a new {@link ChannelFuture} which is marked as failed already. So {@link ChannelFuture#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     *
     * 创建一个已标记为失败的新ChannelFuture 。
     * 所以ChannelFuture.isSuccess()将返回false 。
     * 所有添加到它的FutureListener都会被直接通知。 此外，每次调用阻塞方法都会返回而不会阻塞。
     */
    ChannelFuture newFailedFuture(Throwable cause);

    /**
     * Return a special ChannelPromise which can be reused for different operations.
     * <p>
     * It's only supported to use
     * it for {@link ChannelOutboundInvoker#write(Object, ChannelPromise)}.
     * </p>
     * <p>
     * Be aware that the returned {@link ChannelPromise} will not support most operations and should only be used
     * if you want to save an object allocation for every write operation. You will not be able to detect if the
     * operation  was complete, only if it failed as the implementation will call
     * {@link ChannelPipeline#fireExceptionCaught(Throwable)} in this case.
     * </p>
     * <strong>Be aware this is an expert feature and should be used with care!</strong>
     *
     *
     * 返回一个特殊的ChannelPromise，它可以被不同的操作重用。
     * 它只支持在 ChannelOutboundInvoker#write(Object, ChannelPromise) 时使用。
     *
     * 请注意，返回的ChannelPromise不支持大多数操作，只有在希望为每个写操作保存对象分配时才应该使用。
     * 您将无法检测操作是否完成，只有当它失败的时候，将调用ChannelPipeline.fireExceptionCaught(Throwable) 知道失败信息。
     *
     * 请注意，这是一个高级特性，应该小心使用!
     */
    ChannelPromise voidPromise();
}
