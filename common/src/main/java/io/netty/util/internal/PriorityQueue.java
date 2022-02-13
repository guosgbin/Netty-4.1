/*
 * Copyright 2017 The Netty Project
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
package io.netty.util.internal;

import java.util.Queue;

public interface PriorityQueue<T> extends Queue<T> {
    /**
     * Same as {@link #remove(Object)} but typed using generics.
     * 和 {@link #remove(Object)} 方法一样，只是使用了泛型
     */
    boolean removeTyped(T node);

    /**
     * Same as {@link #contains(Object)} but typed using generics.
     * 和 {@link #contains(Object)} 方法一样，只是使用了泛型
     */
    boolean containsTyped(T node);

    /**
     * Notify the queue that the priority for {@code node} has changed. The queue will adjust to ensure the priority
     * queue properties are maintained.
     *
     * 假如优先队列中的某个元素的值发生改变了，就可以使用这个方法修改优先级了
     *
     * @param node An object which is in this queue and the priority may have changed.
     */
    void priorityChanged(T node);

    /**
     * Removes all of the elements from this {@link PriorityQueue} without calling
     * {@link PriorityQueueNode#priorityQueueIndex(DefaultPriorityQueue)} or explicitly removing references to them to
     * allow them to be garbage collected. This should only be used when it is certain that the nodes will not be
     * re-inserted into this or any other {@link PriorityQueue} and it is known that the {@link PriorityQueue} itself
     * will be garbage collected after this call.
     *
     * 只有在确定删除的节点不会被重新插入到这个或任何其他的PriorityQueue 中，
     * 并且这个PriorityQueue 队列本身将在调用之后被垃圾回收时，才应该使用这个方法。
     */
    void clearIgnoringIndexes();
}
