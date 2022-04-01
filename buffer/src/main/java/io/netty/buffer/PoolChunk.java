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
package io.netty.buffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 *           page页是可以分配的最小内存块单位
 * > run   - a run is a collection of pages
 *           run是pages的集合
 * > chunk - a chunk is a collection of runs
 *           chunk块是runs的集合
 * > in this code chunkSize = maxPages * pageSize
*              poolChunk管理的内存大小 = 最大页数 * 页内存大小
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * 从数组的第一个位置开始找，直到找到一个能个容纳 业务需要的大小 的位置
 *
 * For simplicity all sizes are normalized according to {@link PoolArena#size2SizeIdx(int)} method.
 * This ensures that when we request for memory segments of size > pageSize the normalizedCapacity
 * equals the next nearest size in {@link SizeClasses}.
 *
 *
 *  A chunk has the following layout:
 *
 *     /-----------------\
 *     | run             |
 *     |                 |
 *     |                 |
 *     |-----------------|
 *     | run             |
 *     |                 |
 *     |-----------------|
 *     | unalloctated    |
 *     | (freed)         |
 *     |                 |
 *     |-----------------|
 *     | subpage         |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | ...             |
 *     | ...             |
 *     | ...             |
 *     |                 |
 *     |                 |
 *     |                 |
 *     \-----------------/
 *
 *
 * handle:
 * -------
 * a handle is a long number, the bit layout of a run looks like:
 *
 * oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *
 * o: runOffset (page offset in the chunk), 15bit    chunk的页面偏移量
 * s: size (number of pages) of this run, 15bit      run中页数
 * u: isUsed?, 1bit                                  是否已经被使用了
 * e: isSubpage?, 1bit                               是否是小规格内存
 * b: bitmapIdx of subpage, zero if it's not subpage, 32bit   小规格内存的位图信息
 *
 * runsAvailMap: 管理所有的run，
 * ------
 * a map which manages all runs (used and not in used).
 * For each run, the first runOffset and last runOffset are stored in runsAvailMap.
 * key: runOffset
 * value: handle
 *
 * runsAvail:优先队列
 * ----------
 * an array of {@link PriorityQueue}.
 * Each queue manages same size of runs.
 * Runs are sorted by offset, so that we always allocate runs with smaller offset.
 * 按照最小偏移量来分配
 *
 *
 * Algorithm:
 * ----------
 *
 *   As we allocate runs, we update values stored in runsAvailMap and runsAvail so that the property is maintained.
 *   当我们分配run时，我们更新存储在 runningAvailMap 和 runningAvail 中的值，以便维护该属性
 *
 * Initialization -
 *  In the beginning we store the initial run which is the whole chunk.
 *  The initial run:
 *  runOffset = 0
 *  size = chunkSize
 *  isUsed = no
 *  isSubpage = no
 *  bitmapIdx = 0
 *
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) find the first avail run using in runsAvails according to size
 * 2) if pages of run is larger than request pages then split it, and save the tailing run
 *    for later using
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) find a not full subpage according to size.
 *    if it already exists just return, otherwise allocate a new PoolSubpage and call init()
 *    note that this subpage object is added to subpagesPool in the PoolArena when we init() it
 * 2) call subpage.allocate()
 *
 * Algorithm: [free(handle, length, nioBuffer)]
 * ----------
 * 1) if it is a subpage, return the slab back into this subpage
 * 2) if the subpage is not used or it is a run, then start free this run
 * 3) merge continuous avail runs
 * 4) save the merged run
 *
 */
final class PoolChunk<T> implements PoolChunkMetric {
    private static final int SIZE_BIT_LENGTH = 15;
    private static final int INUSED_BIT_LENGTH = 1;
    private static final int SUBPAGE_BIT_LENGTH = 1;
    private static final int BITMAP_IDX_BIT_LENGTH = 32;

    // 32
    static final int IS_SUBPAGE_SHIFT = BITMAP_IDX_BIT_LENGTH;
    // 33
    static final int IS_USED_SHIFT = SUBPAGE_BIT_LENGTH + IS_SUBPAGE_SHIFT;
    // 34
    static final int SIZE_SHIFT = INUSED_BIT_LENGTH + IS_USED_SHIFT; // 34
    // 49
    static final int RUN_OFFSET_SHIFT = SIZE_BIT_LENGTH + SIZE_SHIFT;

    // 当前PoolChunk所属的Arena
    final PoolArena<T> arena;
    final Object base;
    // 给PoolChunk真正分配的内存
    final T memory;
    // 是否池化内存 true不是 false是
    final boolean unpooled;

    /**
     * store the first page and last page of each avail run
     * 存储的是有用的run中的第一个和最后一个Page的句柄
     */
    private final LongLongHashMap runsAvailMap;

    /**
     * manage all avail runs
     * 管理所有可用的run，这个数组的索引是SizeClasses中的page2PageIdx计算出来的idx
     * 也就是sizeClass中的每个size 一个优先队列进行存储
     *
     * 小顶堆，存储 long 类型的句柄值
     * 通过 LongPriorityQueue.poll() 方法每次都能获取小顶堆内部的最小的 handle 值。
     * 这表示我们每次申请内存都是从最低位地址开始分配。
     * runsAvail数组默认长度为40，所有存储在 LongPriorityQueue 对象的 handle 都表示一个可用的内存块 run。
     * 并且可分配pageSize大于等于(pageIdx=index)上的pageSize，小于(pageIdex=index+1)的pageSize。
     */
    private final LongPriorityQueue[] runsAvail;

    /**
     * manage all subpages in this chunk
     * 管理这个poolChunk中的所有poolSubPage
     * 默认2048个8k的page页面
     */
    private final PoolSubpage<T>[] subpages;

    // 页内存大小 默认8k
    private final int pageSize;
    // 1左移pageShifts位得到pageSize  默认13
    private final int pageShifts;
    // PoolChunk管理的内存大小 默认16mb
    private final int chunkSize;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    // 主要是对PooledByteBuf中频繁创建的ByteBuffer进行缓存，以避免由于频繁创建对象导致频繁GC
    private final Deque<ByteBuffer> cachedNioBuffers;

    // 剩余可用大小
    int freeBytes;

    // 当前PoolChunk所属的PoolChunkList
    PoolChunkList<T> parent;
    // 双向链表中 当前PoolChunk的前一个的PoolChunk
    PoolChunk<T> prev;
    // 双向链表中 当前PoolChunk的后一个的PoolChunk
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 创建一个PoolChunk实例
     *
     * @param arena PoolChunk所属的Arena
     * @param base 能够分配的内存 默认16mb
     * @param memory 能够分配的内存 默认16mb
     * @param pageSize 页内存大小 默认8k
     * @param pageShifts 默认13
     * @param chunkSize 默认16mb
     * @param maxPageIdx 最大页面 索引  默认40
     */
    @SuppressWarnings("unchecked")
    PoolChunk(PoolArena<T> arena, Object base, T memory, int pageSize, int pageShifts, int chunkSize, int maxPageIdx) {
        unpooled = false;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        freeBytes = chunkSize;

        // 创建长度为 40 的 LongPriorityQueue 数组
        // 长度为 40 对应 sizeClasses 数组中是 pagesize 倍数的内存的个数
        runsAvail = newRunsAvailqueueArray(maxPageIdx);
        runsAvailMap = new LongLongHashMap(-1);
        // chunkSize >> pageShifts 默认2048
        subpages = new PoolSubpage[chunkSize >> pageShifts];

        //insert initial run, offset = 0, pages = chunkSize / pageSize
        int pages = chunkSize >> pageShifts;
        // 0b 0010 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
        long initHandle = (long) pages << SIZE_SHIFT;
        // insertAvailRun方法在runsAvail数组最后位置插入一个handle，该handle代表page偏移位置为0的地方可以分配16M的内存块
        insertAvailRun(0, pages, initHandle);

        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, Object base, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        pageSize = 0;
        pageShifts = 0;
        runsAvailMap = null;
        runsAvail = null;
        subpages = null;
        chunkSize = size;
        cachedNioBuffers = null;
    }

    /**
     * 创建一个指定大小的 LongPriorityQueue 数组
     *Git
     * @param size maxPageIdx属性 默认40
     * @return
     */
    private static LongPriorityQueue[] newRunsAvailqueueArray(int size) {
        LongPriorityQueue[] queueArray = new LongPriorityQueue[size];
        for (int i = 0; i < queueArray.length; i++) {
            queueArray[i] = new LongPriorityQueue();
        }
        return queueArray;
    }

    /**
     * 添加到runsAvail中
     *
     * @param runOffset 偏移量
     * @param pages 页数
     * @param handle handle句柄
     */
    private void insertAvailRun(int runOffset, int pages, long handle) {
        // 将句柄信息写入对应的小顶堆
        // 根据页数量向下取整，获得「pageIdxFloor」，这个值即将写入对应runsAvail数组索引的值
        // 根据页数获得page的索引pageIdx -1 ，eg. pages=2048,则pageIdx=40，故pageIdxFloor-1=39
        int pageIdxFloor = arena.pages2pageIdxFloor(pages);
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        queue.offer(handle);

        //insert first page of run
        // 将首页和末页的偏移量和句柄值记录在runsAvailMap对象，待合并run时使用
        insertAvailRun0(runOffset, handle);
        if (pages > 1) {
            //insert last page of run
            // 当页数量超过1时才会记录末页的偏移量和句柄值
            insertAvailRun0(lastPage(runOffset, pages), handle);
        }
    }

    private void insertAvailRun0(int runOffset, long handle) {
        long pre = runsAvailMap.put(runOffset, handle);
        assert pre == -1;
    }

    private void removeAvailRun(long handle) {
        int pageIdxFloor = arena.pages2pageIdxFloor(runPages(handle));
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        removeAvailRun(queue, handle);
    }

    private void removeAvailRun(LongPriorityQueue queue, long handle) {
        queue.remove(handle);

        // 获取当前句柄的 页数 的偏移量，也就是在 chunk 中的第几页
        int runOffset = runOffset(handle);
        // 连续有几个 page
        int pages = runPages(handle);
        //remove first page of run
        runsAvailMap.remove(runOffset);
        if (pages > 1) {
            //remove last page of run
            runsAvailMap.remove(lastPage(runOffset, pages));
        }
    }

    private static int lastPage(int runOffset, int pages) {
        return runOffset + pages - 1;
    }

    private long getAvailRunByOffset(int runOffset) {
        return runsAvailMap.get(runOffset);
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache cache) {
        final long handle;
        if (sizeIdx <= arena.smallMaxSizeIdx) {
            // small
            handle = allocateSubpage(sizeIdx);
            if (handle < 0) {
                return false;
            }
            assert isSubpage(handle);
        } else {
            // normal
            // runSize must be multiple of pageSize
            int runSize = arena.sizeIdx2size(sizeIdx);
            handle = allocateRun(runSize);
            if (handle < 0) {
                return false;
            }
        }

        ByteBuffer nioBuffer = cachedNioBuffers != null? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity, cache);
        return true;
    }

    /**
     * 分配内存
     *
     * @param runSize 需要分配的内存大小
     * @return
     */
    private long allocateRun(int runSize) {
        // 计算所需 page 的数量
        int pages = runSize >> pageShifts;
        // 因为runAvail这个数组用的则是这个idx为索引的
        // pages2pageIdx方法会将申请内存大小对齐为上述Page表格中的一个size。
        // 例如申请172032字节(21个page)的内存块，pages2pageIdx方法计算结果为13，实际分配196608(24个page)的内存块。

        // 根据page的数量计算对应的pageIdx，也就是获取 pageIdx2sizeTab[] 的索引
        // 假如申请 33k ，pages是 5，pages2pageIdx方法计算结果pageIdx为4， 4 位置的大小是 40960
        int pageIdx = arena.pages2pageIdx(pages);
        // 这里的runsAvail保证的是runsAvail和runsAvailMap数据的同步
        synchronized (runsAvail) {
            //find first queue which has at least one big enough run
            // 从当前的pageIdx开始遍历runsAvail,从ranAvail中找到最近的能进行此次分配idx索引
            int queueIdx = runFirstBestFit(pageIdx);
            if (queueIdx == -1) {
                return -1;
            }

            //get run with min offset in this queue
            // 从这个索引中获取对应的run的数据,即为能进行此次分配的空闲段
            LongPriorityQueue queue = runsAvail[queueIdx];
            // 通过 LongPriorityQueue.poll() 方法每次都能获取小顶堆内部的最小的 handle 值。这表示我们每次申请内存都是从最低位地址开始分配
            // 所有存储在 LongPriorityQueue 对象的 handle 都表示一个可用的内存块 run。
            // 并且可分配pageSize大于等于(pageIdx=index)上的pageSize，小于(pageIdex=index+1)的pageSize。
            // 找到一个内存句柄
            long handle = queue.poll();

            assert handle != LongPriorityQueue.NO_VALUE && !isUsed(handle) : "invalid handle: " + handle;

            // 从runsAvail和runsAvailMap中移除这个handle
            removeAvailRun(queue, handle);

            if (handle != -1) {
                // 将这一块内存进行切分，剩余空闲的内存继续存储到ranAvail和runsAvailMap中
                handle = splitLargeRun(handle, pages);
            }
            // 更新freeBytes,减少可用内存字节数
            freeBytes -= runSize(pageShifts, handle);
            return handle;
        }
    }

    private int calculateRunSize(int sizeIdx) {
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        int runSize = 0;
        int nElements;

        final int elemSize = arena.sizeIdx2size(sizeIdx);

        //find lowest common multiple of pageSize and elemSize
        do {
            runSize += pageSize;
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        while (nElements > maxElements) {
            runSize -= pageSize;
            nElements = runSize / elemSize;
        }

        assert nElements > 0;
        assert runSize <= chunkSize;
        assert runSize >= elemSize;

        return runSize;
    }

    /**
     * 从pageIdx开始搜索最合适的run用于内存分配
     *
     * @param pageIdx
     * @return
     */
    private int runFirstBestFit(int pageIdx) {
        if (freeBytes == chunkSize) {
            // 假如这个 poolChunk 还未开始分配过，第一次分配 39 位置
            return arena.nPSizes - 1;
        }
        // 从pageIdx向后遍历，找到queue!=null且不为空的LongPriorityQueue
        for (int i = pageIdx; i < arena.nPSizes; i++) {
            LongPriorityQueue queue = runsAvail[i];
            if (queue != null && !queue.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private long splitLargeRun(long handle, int needPages) {
        assert needPages > 0;

        // 从handle中获取run管理的空闲的page数量
        int totalPages = runPages(handle);
        assert needPages <= totalPages;

        // 分配后剩余page数
        int remPages = totalPages - needPages;

        // 如果还有剩余，需要重新生成run（由handle具象化）并写入两个重要的数据结构中,
        // 一个是 LongLongHashMap runsAvailMap，另一个是 LongPriorityQueue[] runsAvail;
        if (remPages > 0) {
            // 获取在 chunk 上的 页的偏移量，也就是第几页
            int runOffset = runOffset(handle);

            // keep track of trailing unused pages for later use
            // 计算剩余page开始偏移量 剩余空闲页偏移量=旧的偏移量+分配页数
            int availOffset = runOffset + needPages;
            // 根据偏移量、页数量以及isUsed状态生成新的句柄变量，这个变量表示一个全新未使用的run
            long availRun = toRunHandle(availOffset, remPages, 0);
            // 将availRun插入到runsAvail，runsAvailMap中
            insertAvailRun(availOffset, remPages, availRun);

            // not avail
            // 生成用于此次分配的句柄变量
            return toRunHandle(runOffset, needPages, 1);
        }

        //mark it as used
        // 恰好满足，只需把handle的isUsed标志位置为1
        handle |= 1L << IS_USED_SHIFT;
        return handle;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity. Any PoolSubpage created / initialized here is added to
     * subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param sizeIdx sizeIdx of normalized size
     *
     * @return index in memoryMap
     */
    private long allocateSubpage(int sizeIdx) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);
        synchronized (head) {
            //allocate a new run
            // 获取申请的size和pageSize的最小公倍数
            // eg.比如申请内存大小为 16Byte，则只需要等分 1 个page
            // eg.而申请内存大小为 28KB，需要等分 7 个 page，因为它 28KB=28672 和 pageSize=8192 的最小公倍数为 57344（57344/8192=7）。这样会有2个28K，
            int runSize = calculateRunSize(sizeIdx);
            //runSize must be multiples of pageSize
            // 申请若干个page，runSize是pageSize的整数倍
            long runHandle = allocateRun(runSize);
            if (runHandle < 0) {
                return -1;
            }

            // 获取页数偏移量，也就是在 chunk 上的第几页
            int runOffset = runOffset(runHandle);
            assert subpages[runOffset] == null;
            // 每份子内存的规格
            int elemSize = arena.sizeIdx2size(sizeIdx);

            PoolSubpage<T> subpage = new PoolSubpage<T>(head, this, pageShifts, runOffset,
                               runSize(pageShifts, runHandle), elemSize);

            // 由PoolChunk记录新创建的PoolSubpage，数组索引值是首页的偏移量，这个值是唯一的，也是记录在句柄值中
            // 因此，在归还内存时会通过句柄值找到对应的PoolSubpge对象
            subpages[runOffset] = subpage;
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages When a subpage is freed from PoolSubpage, it might be added back to subpage pool
     * of the owning PoolArena. If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize,
     * we can completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, int normCapacity, ByteBuffer nioBuffer) {
        if (isSubpage(handle)) {
            int sizeIdx = arena.size2SizeIdx(normCapacity);
            PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);

            int sIdx = runOffset(handle);
            PoolSubpage<T> subpage = subpages[sIdx];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            synchronized (head) {
                if (subpage.free(head, bitmapIdx(handle))) {
                    //the subpage is still used, do not free it
                    return;
                }
                assert !subpage.doNotDestroy;
                // Null out slot in the array as it was freed and we should not use it anymore.
                subpages[sIdx] = null;
            }
        }

        //start free run
        int pages = runPages(handle);

        synchronized (runsAvail) {
            // collapse continuous runs, successfully collapsed runs
            // will be removed from runsAvail and runsAvailMap
            long finalRun = collapseRuns(handle);

            //set run as not used
            finalRun &= ~(1L << IS_USED_SHIFT);
            //if it is a subpage, set it to run
            finalRun &= ~(1L << IS_SUBPAGE_SHIFT);

            insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun);
            freeBytes += pages << pageShifts;
        }

        if (nioBuffer != null && cachedNioBuffers != null &&
            cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    private long collapseRuns(long handle) {
        return collapseNext(collapsePast(handle));
    }

    private long collapsePast(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long pastRun = getAvailRunByOffset(runOffset - 1);
            if (pastRun == -1) {
                return handle;
            }

            int pastOffset = runOffset(pastRun);
            int pastPages = runPages(pastRun);

            //is continuous
            if (pastRun != handle && pastOffset + pastPages == runOffset) {
                //remove past run
                removeAvailRun(pastRun);
                handle = toRunHandle(pastOffset, pastPages + runPages, 0);
            } else {
                return handle;
            }
        }
    }

    private long collapseNext(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long nextRun = getAvailRunByOffset(runOffset + runPages);
            if (nextRun == -1) {
                return handle;
            }

            int nextOffset = runOffset(nextRun);
            int nextPages = runPages(nextRun);

            //is continuous
            if (nextRun != handle && runOffset + runPages == nextOffset) {
                //remove next run
                removeAvailRun(nextRun);
                handle = toRunHandle(runOffset, runPages + nextPages, 0);
            } else {
                return handle;
            }
        }
    }

    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) runPages << SIZE_SHIFT
               | (long) inUsed << IS_USED_SHIFT;
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        if (isRun(handle)) {
            buf.init(this, nioBuffer, handle, runOffset(handle) << pageShifts,
                     reqCapacity, runSize(pageShifts, handle), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        int runOffset = runOffset(handle);
        int bitmapIdx = bitmapIdx(handle);

        PoolSubpage<T> s = subpages[runOffset];
        assert s.doNotDestroy;
        assert reqCapacity <= s.elemSize;

        int offset = (runOffset << pageShifts) + bitmapIdx * s.elemSize;
        buf.init(this, nioBuffer, handle, offset, reqCapacity, s.elemSize, threadCache);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }

    static int runOffset(long handle) {
        return (int) (handle >> RUN_OFFSET_SHIFT);
    }

    static int runSize(int pageShifts, long handle) {
        return runPages(handle) << pageShifts;
    }

    static int runPages(long handle) {
        return (int) (handle >> SIZE_SHIFT & 0x7fff);
    }

    static boolean isUsed(long handle) {
        return (handle >> IS_USED_SHIFT & 1) == 1L;
    }

    static boolean isRun(long handle) {
        return !isSubpage(handle);
    }

    static boolean isSubpage(long handle) {
        return (handle >> IS_SUBPAGE_SHIFT & 1) == 1L;
    }

    static int bitmapIdx(long handle) {
        return (int) handle;
    }
}
