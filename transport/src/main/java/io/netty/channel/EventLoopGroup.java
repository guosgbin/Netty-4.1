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

import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Special {@link EventExecutorGroup} which allows registering {@link Channel}s that get
 * processed for later selection during the event loop.
 *
 * 特殊的EventExecutorGroup允许注册在事件循环期间处理以供以后选择的Channel 。
 *
 * =====================================================
 * EventLoopGroup 继承了 EventExecutorGroup 接口，
 * 提供了最主要的方法 register， 能够将事件执行器和通道 Channel 进行绑定，
 * 这样事件执行器就可以处理通道 Channel的 I/O操作,也就变成了事件轮询器 EventLoop。
 */
public interface EventLoopGroup extends EventExecutorGroup {
    /**
     * Return the next {@link EventLoop} to use
     *
     * 返回下一个 EventLoop
     * 复写了 EventExecutorGroup 的方法，改变了返回值类型
     */
    @Override
    EventLoop next();

    /**
     * Register a {@link Channel} with this {@link EventLoop}. The returned {@link ChannelFuture}
     * will get notified once the registration was complete.
     *
     * 向这个EventLoop注册一个Channel，一旦注册完成，返回的 ChannelFuture 将得到通知。
     */
    ChannelFuture register(Channel channel);

    /**
     * Register a {@link Channel} with this {@link EventLoop} using a {@link ChannelFuture}. The passed
     * {@link ChannelFuture} will get notified once the registration was complete and also will get returned.
     *
     * 使用参数 ChannelPromise 向 EventLoop 注册一个Channel。 （ChannelPromise类中包含一个Channel）
     * 一旦注册完成，传递的 ChannelPromise 将得到通知，返回的 ChannelFuture 也将得到通知。
     */
    ChannelFuture register(ChannelPromise promise);

    /**
     * Register a {@link Channel} with this {@link EventLoop}. The passed {@link ChannelFuture}
     * will get notified once the registration was complete and also will get returned.
     *
     * @deprecated Use {@link #register(ChannelPromise)} instead.
     */
    @Deprecated
    ChannelFuture register(Channel channel, ChannelPromise promise);
}
