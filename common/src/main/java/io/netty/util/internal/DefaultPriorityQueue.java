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
package io.netty.util.internal;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static io.netty.util.internal.PriorityQueueNode.INDEX_NOT_IN_QUEUE;

/**
 * A priority queue which uses natural ordering of elements. Elements are also required to be of type
 * {@link PriorityQueueNode} for the purpose of maintaining the index in the priority queue.
 * @param <T> The object that is maintained in the queue.
 *
 *  使用元素自然排序的优先级队列。 为了维护优先级队列中的索引，元素也必须是PriorityQueueNode类型。
 */
public final class DefaultPriorityQueue<T extends PriorityQueueNode> extends AbstractQueue<T>
                                                                     implements PriorityQueue<T> {
    private static final PriorityQueueNode[] EMPTY_ARRAY = new PriorityQueueNode[0];
    // 使用比较器 Comparator 进行排序
    private final Comparator<T> comparator;
    // 使用数组存储堆
    private T[] queue;
    // 表示队列中节点的数量
    private int size;

    @SuppressWarnings("unchecked")
    public DefaultPriorityQueue(Comparator<T> comparator, int initialSize) {
        this.comparator = ObjectUtil.checkNotNull(comparator, "comparator");
        queue = (T[]) (initialSize != 0 ? new PriorityQueueNode[initialSize] : EMPTY_ARRAY);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean contains(Object o) {
        // 不是 PriorityQueueNode 的实例，直接返回 false
        if (!(o instanceof PriorityQueueNode)) {
            return false;
        }
        PriorityQueueNode node = (PriorityQueueNode) o;
        // 是 PriorityQueueNode 实例，就可以利用索引快速判断
        return contains(node, node.priorityQueueIndex(this));
    }

    @Override
    public boolean containsTyped(T node) {
        return contains(node, node.priorityQueueIndex(this));
    }

    /**
     * 清空优先队列中的元素
     * 假如数组中的元素存在，将节点的索引状态设置为-1，表示节点不在队列中了
     */
    @Override
    public void clear() {
        // 遍历队列中的元素节点
        for (int i = 0; i < size; ++i) {
            T node = queue[i];
            if (node != null) {
                // 设置节点的索引是 INDEX_NOT_IN_QUEUE，表示节点不在队列中了
                node.priorityQueueIndex(this, INDEX_NOT_IN_QUEUE);
                // 将引用设置为 null， 方便 gc
                queue[i] = null;
            }
        }
        size = 0;
    }

    /**
     * 可以看出 clearIgnoringIndexes() 只是设置了 size的值，
     * 只有在确定节点不会被重新插入到这个或任何其他的PriorityQueue 中，
     * 并且这个PriorityQueue 队列本身将在调用之后被垃圾回收时，才应该使用这个方法。
     */
    @Override
    public void clearIgnoringIndexes() {
        size = 0;
    }

    /**
     * 添加元素到优先队列
     */
    @Override
    public boolean offer(T e) {
        // 根据索引判断，当前待添加的元素是否在队列中
        if (e.priorityQueueIndex(this) != INDEX_NOT_IN_QUEUE) {
            throw new IllegalArgumentException("e.priorityQueueIndex(): " + e.priorityQueueIndex(this) +
                    " (expected: " + INDEX_NOT_IN_QUEUE + ") + e: " + e);
        }

        // Check that the array capacity is enough to hold values by doubling capacity.
        // 检查节点数量 size 是否已经超过数组queue容量。
        // 如果是的话，就将数组queue容量扩大
        if (size >= queue.length) {
            // Use a policy which allows for a 0 initial capacity. Same policy as JDK's priority queue, double when
            // "small", then grow by 50% when "large".
            // 使用允许初始容量为0的策略。
            // 与JDK的优先级队列策略相同，“小”时加倍，“大”时增加50%。
            queue = Arrays.copyOf(queue, queue.length + ((queue.length < 64) ?
                                                         (queue.length + 2) :
                                                         (queue.length >>> 1)));
        }
        // 计划在最后一个位置插入节点元素e，
        // 然后向上遍历树，保持最小堆属性。
        bubbleUp(size++, e);
        return true;
    }

    /**
     * 返回堆顶元素
     */
    @Override
    public T poll() {
        // 如果队列为空，返回 null
        if (size == 0) {
            return null;
        }
        // 记录树根节点
        T result = queue[0];
        // 设置树根节点的索引值
        result.priorityQueueIndex(this, INDEX_NOT_IN_QUEUE);

        // 得到堆最后一个节点， 并将队列节点数量 size 值减一
        T last = queue[--size];
        queue[size] = null;
        if (size != 0) { // Make sure we don't add the last element back.
            // 从树根向下遍历，保持最小堆属性。
            bubbleDown(0, last);
        }

        return result;
    }

    /**
     * 查看堆顶元素
     */
    @Override
    public T peek() {
        return (size == 0) ? null : queue[0];
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean remove(Object o) {
        final T node;
        try {
            // 只有是 PriorityQueueNode 的实例，才有可能删除
            node = (T) o;
        } catch (ClassCastException e) {
            return false;
        }
        return removeTyped(node);
    }

    @Override
    public boolean removeTyped(T node) {
        // 获取节点对应的索引，可以快速查找
        int i = node.priorityQueueIndex(this);
        // 队列是否包含这个节点node
        if (!contains(node, i)) {
            return false;
        }

        // 改变这个节点的索引
        node.priorityQueueIndex(this, INDEX_NOT_IN_QUEUE);
        if (--size == 0 || size == i) {
            // If there are no node left, or this is the last node in the array just remove and return.
            // 如果没有节点剩下，或者这是数组中的最后一个节点，就不涉及到树的改动了，直接删除并返回
            queue[i] = null;
            return true;
        }

        // Move the last element where node currently lives in the array.
        // 将最后一个节点值移动到要删除节点位置i
        T moved = queue[i] = queue[size];
        // 最后一个节点值设置为 null
        queue[size] = null;
        // priorityQueueIndex will be updated below in bubbleUp or bubbleDown

        // Make sure the moved node still preserves the min-heap properties.
        // 为了确保移动的节点仍然保留最小堆属性
        if (comparator.compare(node, moved) < 0) {
            // 删除节点值node 小于 最后一个节点值 moved，
            // 这就说明 moved 放到 i 位置，肯定是大于 i 的父节点的值，
            // 那么从 i 向上的树是满足最小堆属性的，
            // 但是从 i 向下的树，就不一定了，
            // 所以需要bubbleDown(i, moved) 方法，保持最小堆属性。
            bubbleDown(i, moved);
        } else {
            // 删除节点值node 大于等于 最后一个节点值 moved，
            // 这就说明 moved 放到 i 位置，肯定是大于 i 的左右子节点的值，
            // 那么从 i 向下的树是满足最小堆属性的，
            // 但是从 i 向上的树，就不一定了，
            // 所以需要 bubbleUp(i, moved) 方法，保持最小堆属性。
            bubbleUp(i, moved);
        }
        return true;
    }

    @Override
    public void priorityChanged(T node) {
        int i = node.priorityQueueIndex(this);
        if (!contains(node, i)) {
            return;
        }

        // Preserve the min-heap property by comparing the new priority with parents/children in the heap.
        if (i == 0) {
            bubbleDown(i, node);
        } else {
            // Get the parent to see if min-heap properties are violated.
            int iParent = (i - 1) >>> 1;
            T parent = queue[iParent];
            if (comparator.compare(node, parent) < 0) {
                bubbleUp(i, node);
            } else {
                bubbleDown(i, node);
            }
        }
    }

    @Override
    public Object[] toArray() {
        return Arrays.copyOf(queue, size);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <X> X[] toArray(X[] a) {
        if (a.length < size) {
            return (X[]) Arrays.copyOf(queue, size, a.getClass());
        }
        System.arraycopy(queue, 0, a, 0, size);
        if (a.length > size) {
            a[size] = null;
        }
        return a;
    }

    /**
     * This iterator does not return elements in any particular order.
     */
    @Override
    public Iterator<T> iterator() {
        return new PriorityQueueIterator();
    }

    private final class PriorityQueueIterator implements Iterator<T> {
        private int index;

        @Override
        public boolean hasNext() {
            return index < size;
        }

        @Override
        public T next() {
            if (index >= size) {
                throw new NoSuchElementException();
            }

            return queue[index++];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }
    }

    /**
     * 判断节点是不是在队列中，
     * 因为节点都是 PriorityQueueNode 类型，可以获取对应的索引 i，
     * 这样就可以直接通过索引从数组queue中获取数据进行比较，提高效率。
     */
    private boolean contains(PriorityQueueNode node, int i) {
        return i >= 0 && i < size && node.equals(queue[i]);
    }

    /**
     * 调用这个方法，表示当前 k 位置的节点值 node 可能比它的子节点的值大；
     * 为了保持最小堆属性，因此向下遍历树，将节点值 node 放到合适的位置
     *
     *     0
     *   1   2
     *  3 4 5
     */
    private void bubbleDown(int k, T node) {
        // size 是树的最底一层， size >>> 1 就表示最底一层节点的父节点
        final int half = size >>> 1;
        // 通过循环，保证父节点的值不能大于子节点。
        while (k < half) {
            // Compare node to the children of index k.
            // 左子节点, 相当于 (k * 2) + 1
            int iChild = (k << 1) + 1;
            // 左节点存储的值 child
            T child = queue[iChild];

            // Make sure we get the smallest child to compare against.
            // 右子节点
            int rightChild = iChild + 1;
            // 当右节点在队列中，且左节点大于右节点，右节点才是较小的子节点，那么进行交换
            if (rightChild < size && comparator.compare(child, queue[rightChild]) > 0) {
                child = queue[iChild = rightChild];
            }
            // If the bubbleDown node is less than or equal to the smallest child then we will preserve the min-heap
            // property by inserting the bubbleDown node here.
            // 当 bubbleDown节点node 的值小于或者等于当前较小的子节点的值，
            // 那么我们将通过在这里插入bubbleDown节点来保持最小堆属性。
            // 直接 break 跳出循环
            if (comparator.compare(node, child) <= 0) {
                break;
            }

            // Bubble the child up.
            // 将较小值的子节点移动到父节点
            queue[k] = child;
            // 通知这个子节点，索引位置改变了
            child.priorityQueueIndex(this, k);

            // Move down k down the tree for the next iteration.
            // 将较小值的子节点位置赋值给 k
            // 即移动到树的下一层，寻找当前bubbleDown节点的位置
            k = iChild;
        }

        // We have found where node should live and still satisfy the min-heap property, so put it in the queue.
        //我们已经找到了节点的位置，并且仍然满足最小堆属性，因此将它放入队列中。
        queue[k] = node;
        node.priorityQueueIndex(this, k);
    }

    /**
     * 调用这个方法，表示当前 k 位置的节点值 node 可能比它的父节点的值小；
     * 为了保持最小堆属性，因此向上遍历树，将节点值 node 放到合适的位置
     *     0
     *   1   2
     *  3 4 5
     */
    private void bubbleUp(int k, T node) {
        // 当k==0时，就到了堆二叉树的根节点了，跳出循环
        while (k > 0) {
            // 父节点位置坐标, 相当于(k - 1) / 2
            int iParent = (k - 1) >>> 1;
            // 获取父节点位置元素
            T parent = queue[iParent];

            // If the bubbleUp node is less than the parent, then we have found a spot to insert and still maintain
            // min-heap properties.
            if (comparator.compare(node, parent) >= 0) {
                // 待添加的元素比父节点大(或等于)，说明已经找到要插入的位置了，直接跳出循环
                break;
            }

            // Bubble the parent down.
            // 前置条件待添加的元素比父节点小，就将父节点元素存放到k位置
            queue[k] = parent;
            // 设置父节点的姓索引为k
            parent.priorityQueueIndex(this, k);

            // Move k up the tree for the next iteration.
            // 重新赋值k，寻找元素key应该插入到堆二叉树的那个节点
            k = iParent;
        }

        // We have found where node should live and still satisfy the min-heap property, so put it in the queue.
        // 我们已经找到了节点的位置，并且仍然满足最小堆属性，因此将它放入队列中。
        queue[k] = node;
        node.priorityQueueIndex(this, k);
    }
}
