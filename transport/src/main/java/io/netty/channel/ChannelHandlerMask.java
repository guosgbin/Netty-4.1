/*
 * Copyright 2019 The Netty Project
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

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.PlatformDependent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 处理 @Skip 注解
 */
final class ChannelHandlerMask {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelHandlerMask.class);

    // Using to mask which methods must be called for a ChannelHandler.
    // 用来标记 ChannelHandler 中的方法，包括入站事件方法和出站事件方法
    // 标记 exceptionCaught 方法
    static final int MASK_EXCEPTION_CAUGHT = 1;
    // 标记 channelRegistered 方法
    static final int MASK_CHANNEL_REGISTERED = 1 << 1;
    // 标记 channelUnregistered 方法
    static final int MASK_CHANNEL_UNREGISTERED = 1 << 2;
    // 标记 channelActive 方法
    static final int MASK_CHANNEL_ACTIVE = 1 << 3;
    // 标记 channelInactive 方法
    static final int MASK_CHANNEL_INACTIVE = 1 << 4;
    // 标记 channelRead 方法
    static final int MASK_CHANNEL_READ = 1 << 5;
    // 标记 channelReadComplete 方法
    static final int MASK_CHANNEL_READ_COMPLETE = 1 << 6;
    // 标记 userEventTriggered 方法
    static final int MASK_USER_EVENT_TRIGGERED = 1 << 7;
    // 标记 channelWritabilityChanged 方法
    static final int MASK_CHANNEL_WRITABILITY_CHANGED = 1 << 8;
    // 标记 bind 方法
    static final int MASK_BIND = 1 << 9;
    // 标记 connect 方法
    static final int MASK_CONNECT = 1 << 10;
    // 标记 disconnect 方法
    static final int MASK_DISCONNECT = 1 << 11;
    // 标记 close 方法
    static final int MASK_CLOSE = 1 << 12;
    // 标记 deregister 方法
    static final int MASK_DEREGISTER = 1 << 13;
    // 标记 read 方法
    static final int MASK_READ = 1 << 14;
    // 标记 write 方法
    static final int MASK_WRITE = 1 << 15;
    // 标记 flush 方法
    static final int MASK_FLUSH = 1 << 16;

    // 计算入站事件掩码
    // 0000 0000 0000 0000 0000 0001 1111 1110
    static final int MASK_ONLY_INBOUND =  MASK_CHANNEL_REGISTERED |
            MASK_CHANNEL_UNREGISTERED | MASK_CHANNEL_ACTIVE | MASK_CHANNEL_INACTIVE | MASK_CHANNEL_READ |
            MASK_CHANNEL_READ_COMPLETE | MASK_USER_EVENT_TRIGGERED | MASK_CHANNEL_WRITABILITY_CHANGED;

    // 计算入站事件的掩码，包含 MASK_EXCEPTION_CAUGHT
    // 0000 0000 0000 0000 0000 0001 1111 1111
    private static final int MASK_ALL_INBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_INBOUND;

    // 计算出出站事件的掩码
    // 0000 0000 0000 0001 1111 1110 0000 0000
    static final int MASK_ONLY_OUTBOUND =  MASK_BIND | MASK_CONNECT | MASK_DISCONNECT |
            MASK_CLOSE | MASK_DEREGISTER | MASK_READ | MASK_WRITE | MASK_FLUSH;

    // 计算出出站事件的掩码 包含 MASK_EXCEPTION_CAUGHT
    // 0000 0000 0000 0001 1111 1110 0000 0001
    private static final int MASK_ALL_OUTBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_OUTBOUND;

    // 用来缓存， ChannelHandler 子类对应的执行标记 mask，就不用每次都需要计算了
    private static final FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>> MASKS =
            new FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>>() {
                @Override
                protected Map<Class<? extends ChannelHandler>, Integer> initialValue() {
                    return new WeakHashMap<Class<? extends ChannelHandler>, Integer>(32);
                }
            };

    /**
     * Return the {@code executionMask}.
     */
    static int mask(Class<? extends ChannelHandler> clazz) {
        // Try to obtain the mask from the cache first. If this fails calculate it and put it in the cache for fast
        // lookup in the future.
        Map<Class<? extends ChannelHandler>, Integer> cache = MASKS.get();
        Integer mask = cache.get(clazz);
        if (mask == null) {
            mask = mask0(clazz);
            cache.put(clazz, mask);
        }
        return mask;
    }

    /**
     * Calculate the {@code executionMask}.
     * 返回值是一个int类型的 二进制
     * 对应下标位 代表指定方法 位的值是1，表示方法handlerType类型中实现了该方法
     * 位置是0表示handlerType类型中 没有实现该方法
     */
    private static int mask0(Class<? extends ChannelHandler> handlerType) {
        // 0000 0000 0000 0000 0000 0000 0000 0001
        int mask = MASK_EXCEPTION_CAUGHT;
        try {
            // 条件成立 说明handlerType类型是属于ChannelInBoundHandler的子类
            if (ChannelInboundHandler.class.isAssignableFrom(handlerType)) {
                // 结果 0000 0000 0000 0000 0000 0001 1111 1111
                mask |= MASK_ALL_INBOUND;

                // 参数1：handler的真实class类型
                // 参数2：检查的方法名
                // 参数3：ChannelHandlerContext.class
                // isSkippable返回handlerType这个class 有没有重写指定的方法，重写之后指定方法上的Skip注解就没有了
                // 条件成立，说明没有重写该方法
                if (isSkippable(handlerType, "channelRegistered", ChannelHandlerContext.class)) {
                    //     0000 0000 0000 0000 0000 0001 1111 1111
                    // 取反 1111 1111 1111 1111 1111 1111 1111 1101
                    // &   0000 0000 0000 0000 0000 0001 1111 1101
                    // 也就是说 假如你自己实现了这些方法 该位置就是1，没有实现该方法 该位置就是0
                    mask &= ~MASK_CHANNEL_REGISTERED;
                }
                if (isSkippable(handlerType, "channelUnregistered", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_UNREGISTERED;
                }
                if (isSkippable(handlerType, "channelActive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_ACTIVE;
                }
                if (isSkippable(handlerType, "channelInactive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_INACTIVE;
                }
                if (isSkippable(handlerType, "channelRead", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_CHANNEL_READ;
                }
                if (isSkippable(handlerType, "channelReadComplete", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_READ_COMPLETE;
                }
                if (isSkippable(handlerType, "channelWritabilityChanged", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_WRITABILITY_CHANGED;
                }
                if (isSkippable(handlerType, "userEventTriggered", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_USER_EVENT_TRIGGERED;
                }
            }

            if (ChannelOutboundHandler.class.isAssignableFrom(handlerType)) {
                mask |= MASK_ALL_OUTBOUND;

                if (isSkippable(handlerType, "bind", ChannelHandlerContext.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_BIND;
                }
                if (isSkippable(handlerType, "connect", ChannelHandlerContext.class, SocketAddress.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_CONNECT;
                }
                if (isSkippable(handlerType, "disconnect", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DISCONNECT;
                }
                if (isSkippable(handlerType, "close", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_CLOSE;
                }
                if (isSkippable(handlerType, "deregister", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DEREGISTER;
                }
                if (isSkippable(handlerType, "read", ChannelHandlerContext.class)) {
                    mask &= ~MASK_READ;
                }
                if (isSkippable(handlerType, "write", ChannelHandlerContext.class,
                        Object.class, ChannelPromise.class)) {
                    mask &= ~MASK_WRITE;
                }
                if (isSkippable(handlerType, "flush", ChannelHandlerContext.class)) {
                    mask &= ~MASK_FLUSH;
                }
            }

            // "exceptionCaught" 方法需要单独判断，因为 ChannelInboundHandler 和 ChannelOutboundHandler 都有它
            if (isSkippable(handlerType, "exceptionCaught", ChannelHandlerContext.class, Throwable.class)) {
                mask &= ~MASK_EXCEPTION_CAUGHT;
            }
        } catch (Exception e) {
            // Should never reach here.
            PlatformDependent.throwException(e);
        }

        return mask;
    }

    // 参数1：handler的真实class类型
    // 参数2：检查的方法名
    // 参数3：ChannelHandlerContext.class
    @SuppressWarnings("rawtypes")
    private static boolean isSkippable(
            final Class<?> handlerType, final String methodName, final Class<?>... paramTypes) throws Exception {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                Method m;
                try {
                    m = handlerType.getMethod(methodName, paramTypes);
                } catch (NoSuchMethodException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                            "Class {} missing method {}, assume we can not skip execution", handlerType, methodName, e);
                    }
                    return false;
                }
                return m.isAnnotationPresent(Skip.class);
            }
        });
    }

    private ChannelHandlerMask() { }

    /**
     * Indicates that the annotated event handler method in {@link ChannelHandler} will not be invoked by
     * {@link ChannelPipeline} and so <strong>MUST</strong> only be used when the {@link ChannelHandler}
     * method does nothing except forward to the next {@link ChannelHandler} in the pipeline.
     * <p>
     * Note that this annotation is not {@linkplain Inherited inherited}. If a user overrides a method annotated with
     * {@link Skip}, it will not be skipped anymore. Similarly, the user can override a method not annotated with
     * {@link Skip} and simply pass the event through to the next handler, which reverses the behavior of the
     * supertype.
     * </p>
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Skip {
        // no value
    }
}
