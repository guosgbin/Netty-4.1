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
package io.netty.util;

/**
 * Holds {@link Attribute}s which can be accessed via {@link AttributeKey}.
 *
 * Implementations must be Thread-safe.
 *
 * 持有 {@link Attribute} 对象，可以通过 Key 来访问
 * 实现类必须是线程安全的
 */
public interface AttributeMap {
    /**
     * Get the {@link Attribute} for the given {@link AttributeKey}. This method will never return null, but may return
     * an {@link Attribute} which does not have a value set yet.
     *
     * 获取 key 对应的 Attribute 对象，
     * 不会返回null，但是可能返回的对象并不包含值
     */
    <T> Attribute<T> attr(AttributeKey<T> key);

    /**
     * Returns {@code true} if and only if the given {@link Attribute} exists in this {@link AttributeMap}.
     *
     * 判断给定 key 对应的值是否在 AttributeMap 中存在
     */
    <T> boolean hasAttr(AttributeKey<T> key);
}
