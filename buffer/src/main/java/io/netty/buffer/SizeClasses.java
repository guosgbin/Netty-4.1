/*
 * Copyright 2020 The Netty Project
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

import static io.netty.buffer.PoolThreadCache.*;

/**
 * SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
 * and it in turn defines:
 * <p>
 *   LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
 *   LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
 *   sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
 *                 isSubPage, log2DeltaLookup] tuples.
 *     index: Size class index.
 *     log2Group: Log of group base size (no deltas added).
 *     log2Delta: Log of delta to previous size class.
 *     nDelta: Delta multiplier.
 *     isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.
 *     isSubPage: 'yes' if a subpage size class, 'no' otherwise.
 *     log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
 *                      otherwise.
 * <p>
 *   nSubpages: Number of subpages size classes.
 *   nSizes: Number of size classes.
 *   nPSizes: Number of size classes that are multiples of pageSize.
 *
 *   smallMaxSizeIdx: Maximum small size class index.
 *
 *   lookupMaxclass: Maximum size class included in lookup table.
 *   log2NormalMinClass: Log of minimum normal size class.
 * <p>
 *   The first size class and spacing are 1 << LOG2_QUANTUM.
 *   Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.
 *
 *   size = 1 << log2Group + nDelta * (1 << log2Delta)
 *
 *   The first size class has an unusual encoding, because the size has to be
 *   split between group and delta*nDelta.
 *
 *   If pageShift = 13, sizeClasses looks like this:
 *
 *   (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * <p>
 *   ( 0,     4,        4,         0,       no,             yes,        4)
 *   ( 1,     4,        4,         1,       no,             yes,        4)
 *   ( 2,     4,        4,         2,       no,             yes,        4)
 *   ( 3,     4,        4,         3,       no,             yes,        4)
 * <p>
 *   ( 4,     6,        4,         1,       no,             yes,        4)
 *   ( 5,     6,        4,         2,       no,             yes,        4)
 *   ( 6,     6,        4,         3,       no,             yes,        4)
 *   ( 7,     6,        4,         4,       no,             yes,        4)
 * <p>
 *   ( 8,     7,        5,         1,       no,             yes,        5)
 *   ( 9,     7,        5,         2,       no,             yes,        5)
 *   ( 10,    7,        5,         3,       no,             yes,        5)
 *   ( 11,    7,        5,         4,       no,             yes,        5)
 *   ...
 *   ...
 *   ( 72,    23,       21,        1,       yes,            no,        no)
 *   ( 73,    23,       21,        2,       yes,            no,        no)
 *   ( 74,    23,       21,        3,       yes,            no,        no)
 *   ( 75,    23,       21,        4,       yes,            no,        no)
 * <p>
 *   ( 76,    24,       22,        1,       yes,            no,        no)
 */

/**
 * | index  | log2Group  | log2Delta  | nDelta  | isMultiPageSize  | isSubPage  | log2DeltaLookup  | size      | usize  |
 * | 0      | 4          | 4          | 0       | 0                | 1          | 4                | 16        |        |
 * | 1      | 4          | 4          | 1       | 0                | 1          | 4                | 32        |        |
 * | 2      | 4          | 4          | 2       | 0                | 1          | 4                | 48        |        |
 * | 3      | 4          | 4          | 3       | 0                | 1          | 4                | 64        |        |
 * | 4      | 6          | 4          | 1       | 0                | 1          | 4                | 80        |        |
 * | 5      | 6          | 4          | 2       | 0                | 1          | 4                | 96        |        |
 * | 6      | 6          | 4          | 3       | 0                | 1          | 4                | 112       |        |
 * | 7      | 6          | 4          | 4       | 0                | 1          | 4                | 128       |        |
 * | 8      | 7          | 5          | 1       | 0                | 1          | 5                | 160       |        |
 * | 9      | 7          | 5          | 2       | 0                | 1          | 5                | 192       |        |
 * | 10     | 7          | 5          | 3       | 0                | 1          | 5                | 224       |        |
 * | 11     | 7          | 5          | 4       | 0                | 1          | 5                | 256       |        |
 * | 12     | 8          | 6          | 1       | 0                | 1          | 6                | 320       |        |
 * | 13     | 8          | 6          | 2       | 0                | 1          | 6                | 384       |        |
 * | 14     | 8          | 6          | 3       | 0                | 1          | 6                | 448       |        |
 * | 15     | 8          | 6          | 4       | 0                | 1          | 6                | 512       |        |
 * | 16     | 9          | 7          | 1       | 0                | 1          | 7                | 640       |        |
 * | 17     | 9          | 7          | 2       | 0                | 1          | 7                | 768       |        |
 * | 18     | 9          | 7          | 3       | 0                | 1          | 7                | 896       |        |
 * | 19     | 9          | 7          | 4       | 0                | 1          | 7                | 1024      | 1K     |
 * | 20     | 10         | 8          | 1       | 0                | 1          | 8                | 1280      | 1.25K  |
 * | 21     | 10         | 8          | 2       | 0                | 1          | 8                | 1536      | 1.5K   |
 * | 22     | 10         | 8          | 3       | 0                | 1          | 8                | 1792      | 1.75K  |
 * | 23     | 10         | 8          | 4       | 0                | 1          | 8                | 2048      | 2K     |
 * | 24     | 11         | 9          | 1       | 0                | 1          | 9                | 2560      | 2.5K   |
 * | 25     | 11         | 9          | 2       | 0                | 1          | 9                | 3072      | 3K     |
 * | 26     | 11         | 9          | 3       | 0                | 1          | 9                | 3584      | 3.5K   |
 * | 27     | 11         | 9          | 4       | 0                | 1          | 9                | 4096      | 4K     |
 * | 28     | 12         | 10         | 1       | 0                | 1          | 0                | 5120      | 5K     |
 * | 29     | 12         | 10         | 2       | 0                | 1          | 0                | 6144      | 6K     |
 * | 30     | 12         | 10         | 3       | 0                | 1          | 0                | 7168      | 7K     |
 * | 31     | 12         | 10         | 4       | 1                | 1          | 0                | 8192      | 8K     |
 * | 32     | 13         | 11         | 1       | 0                | 1          | 0                | 10240     | 10K    |
 * | 33     | 13         | 11         | 2       | 0                | 1          | 0                | 12288     | 12K    |
 * | 34     | 13         | 11         | 3       | 0                | 1          | 0                | 14336     | 14K    |
 * | 35     | 13         | 11         | 4       | 1                | 1          | 0                | 16384     | 16K    |
 * | 36     | 14         | 12         | 1       | 0                | 1          | 0                | 20480     | 20K    |
 * | 37     | 14         | 12         | 2       | 1                | 1          | 0                | 24576     | 24K    |
 * | 38     | 14         | 12         | 3       | 0                | 1          | 0                | 28672     | 28K    |
 * | 39     | 14         | 12         | 4       | 1                | 0          | 0                | 32768     | 32K    |
 * | 40     | 15         | 13         | 1       | 1                | 0          | 0                | 40960     | 40K    |
 * | 41     | 15         | 13         | 2       | 1                | 0          | 0                | 49152     | 48K    |
 * | 42     | 15         | 13         | 3       | 1                | 0          | 0                | 57344     | 56K    |
 * | 43     | 15         | 13         | 4       | 1                | 0          | 0                | 65536     | 64K    |
 * | 44     | 16         | 14         | 1       | 1                | 0          | 0                | 81920     | 80K    |
 * | 45     | 16         | 14         | 2       | 1                | 0          | 0                | 98304     | 96K    |
 * | 46     | 16         | 14         | 3       | 1                | 0          | 0                | 114688    | 112K   |
 * | 47     | 16         | 14         | 4       | 1                | 0          | 0                | 131072    | 128K   |
 * | 48     | 17         | 15         | 1       | 1                | 0          | 0                | 163840    | 160K   |
 * | 49     | 17         | 15         | 2       | 1                | 0          | 0                | 196608    | 192K   |
 * | 50     | 17         | 15         | 3       | 1                | 0          | 0                | 229376    | 224K   |
 * | 51     | 17         | 15         | 4       | 1                | 0          | 0                | 262144    | 256K   |
 * | 52     | 18         | 16         | 1       | 1                | 0          | 0                | 327680    | 320K   |
 * | 53     | 18         | 16         | 2       | 1                | 0          | 0                | 393216    | 384K   |
 * | 54     | 18         | 16         | 3       | 1                | 0          | 0                | 458752    | 448K   |
 * | 55     | 18         | 16         | 4       | 1                | 0          | 0                | 524288    | 512K   |
 * | 56     | 19         | 17         | 1       | 1                | 0          | 0                | 655360    | 640K   |
 * | 57     | 19         | 17         | 2       | 1                | 0          | 0                | 786432    | 768K   |
 * | 58     | 19         | 17         | 3       | 1                | 0          | 0                | 917504    | 896K   |
 * | 59     | 19         | 17         | 4       | 1                | 0          | 0                | 1048576   | 1M     |
 * | 60     | 20         | 18         | 1       | 1                | 0          | 0                | 1310720   | 1.25M  |
 * | 61     | 20         | 18         | 2       | 1                | 0          | 0                | 1572864   | 1.5M   |
 * | 62     | 20         | 18         | 3       | 1                | 0          | 0                | 1835008   | 1.75M  |
 * | 63     | 20         | 18         | 4       | 1                | 0          | 0                | 2097152   | 2M     |
 * | 64     | 21         | 19         | 1       | 1                | 0          | 0                | 2621440   | 2.5M   |
 * | 65     | 21         | 19         | 2       | 1                | 0          | 0                | 3145728   | 3M     |
 * | 66     | 21         | 19         | 3       | 1                | 0          | 0                | 3670016   | 3.5M   |
 * | 67     | 21         | 19         | 4       | 1                | 0          | 0                | 4194304   | 4M     |
 * | 68     | 22         | 20         | 1       | 1                | 0          | 0                | 5242880   | 5M     |
 * | 69     | 22         | 20         | 2       | 1                | 0          | 0                | 6291456   | 6M     |
 * | 70     | 22         | 20         | 3       | 1                | 0          | 0                | 7340032   | 7M     |
 * | 71     | 22         | 20         | 4       | 1                | 0          | 0                | 8388608   | 8M     |
 * | 72     | 23         | 21         | 1       | 1                | 0          | 0                | 10485760  | 10M    |
 * | 73     | 23         | 21         | 2       | 1                | 0          | 0                | 12582912  | 12M    |
 * | 74     | 23         | 21         | 3       | 1                | 0          | 0                | 14680064  | 14M    |
 * | 75     | 23         | 21         | 4       | 1                | 0          | 0                | 16777216  | 16M    |
 */

/**
 * netty中SizeClasses是PoolArena的父类，
 * 其主要是根据pageSize,chunkSize等信息根据上面的规则生成对应的size的表格，并提供索引到size，size到索引的映射等功能
 * 以便其他组件PoolChunk,PoolArena,PoolSubPage等判断其是否为subPage,
 * 以及利用size和索引的映射关系来建立索引相关的池表示每种size的对应的池。
 *
 * Netty内存池中每个内存块size都符合如下计算公式
 *
 * size = 1 << log2Group + nDelta * (1 << log2Delta)
 * 简单看就是 size = 2^log2Group + nDelta * 2^log2Delta
 *
 * log2Group：内存块分组
 * nDelta：增量乘数
 * log2Delta：增量大小的log2值
 *
 * SizeClasses初始化后，将计算chunkSize（内存池每次向操作系统申请内存块大小）范围内每个size的值，保存到sizeClasses字段中。
 * sizeClasses是一个表格（二维数组），共有7列，含义如下
 *      index:内存块size的索引
 *      log2Group:内存块分组，用于计算对应的size
 *      log2Delata:增量大小的log2值，用于计算对应的size
 *      nDelta:增量乘数，用于计算对应的size
 *      isMultipageSize:表示size是否为page的倍数
 *      isSubPage:表示是否为一个subPage类型
 *      log2DeltaLookup:如果size存在位图中的，记录其log2Delta，未使用
 *
 * sizeClasses表格可以分为两部分，isSubPage为1的size为Small内存块，其他为Normal内存块。
 * 分配Small内存块，需要找到对应的index，通过size2SizeIdx方法计算index
 * 比如需要分配一个90字节的内存块，需要从sizeClasses表格找到第一个大于90的内存块size，即96，其index为5。
 *
 * Normal内存块必须是page的倍数。将isMultipageSize为1的行取出组成另一个表格
 *
 */
abstract class SizeClasses implements SizeClassesMetric {

    static final int LOG2_QUANTUM = 4;

    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;

    // index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup
    // 二维数组每列的含义
    private static final int INDEX_IDX = 0;
    private static final int LOG2GROUP_IDX = 1;
    private static final int LOG2DELTA_IDX = 2;
    private static final int NDELTA_IDX = 3;
    private static final int PAGESIZE_IDX = 4;
    private static final int SUBPAGE_IDX = 5;
    private static final int LOG2_DELTA_LOOKUP_IDX = 6;

    private static final byte no = 0, yes = 1;

    protected SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        // 页内存大小 默认8k
        this.pageSize = pageSize;
        // 默认13
        this.pageShifts = pageShifts;
        // 默认16mb
        this.chunkSize = chunkSize;
        // 主要是对于Huge这种直接分配的类型的数据将其对其为directMemoryCacheAlignment的倍数
        this.directMemoryCacheAlignment = directMemoryCacheAlignment;

        // 计算出group的数量 24 + 1 - 4 = 21
        int group = log2(chunkSize) + 1 - LOG2_QUANTUM;

        //generate size classes
        //[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        // 创建short二维数组，group 21左移2位 21 * 4 = 84 new short[84][7]
        sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7];
        // 初始化sizeClasses数组，返回的int是数组的有效索引个数 默认76
        nSizes = sizeClasses();

        //generate lookup table

        // 生成idx对size的表格,这里的sizeIdx2sizeTab存储的就是利用(1 << log2Group) + (nDelta << log2Delta)计算的size
        // pageIdx2sizeTab则存储的是isMultiPageSize是1的对应的size
        sizeIdx2sizeTab = new int[nSizes];
        pageIdx2sizeTab = new int[nPSizes];
        idx2SizeTab(sizeIdx2sizeTab, pageIdx2sizeTab);

        //生成size对idx的表格,这里存储的是lookupMaxSize以下的,并且其size的单位是1<<LOG2_QUANTUM
        size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        // [0, 1, 2, 3, 4, 5, 6, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 12, 12, 13, 13, 13, 13, 14, 14, 14, 14, 15, 15, 15, 15, 16, 16, 16, 16, 16, 16, 16, 16, 17, 17, 17, 17, 17, 17, 17, 17, 18, 18, 18, 18, 18, 18, 18, 18, 19, 19, 19, 19, 19, 19, 19, 19, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 22, 22, 22, 22, +156 more]
        size2idxTab(size2idxTab);
    }

    // 一页内存大小 默认8k
    protected final int pageSize;
    // 1左移多少位得到pageSize 默认13位
    protected final int pageShifts;
    // 一个chunk能够管理的内存大小默认16mb
    protected final int chunkSize;
    // 内存对齐 默认0
    protected final int directMemoryCacheAlignment;

    // sizeClasses有效位索引个数 76个
    final int nSizes;
    // 小于pageSize的 内存规格的个数 39个
    int nSubpages;
    // n倍pageSize的 内存规格 个数 40个
    int nPSizes;
    // 判断分配是从subpage分配还是直接从poolchunk中进行分配  38
    int smallMaxSizeIdx;

    // 默认4096
    private int lookupMaxSize;

    // 为上面的存储了log2Group等7个数据的表格
    private final short[][] sizeClasses;
    // 存储的是上面的表格中isMultiPages是1的对应的size的数据（主要用于监控的数据）
    // [8192, 16384, 24576, 32768, 40960, 49152, 57344, 65536, 81920, 98304, 114688, 131072, 163840, 196608, 229376, 262144, 327680, 393216, 458752, 524288, 655360, 786432, 917504, 1048576, 1310720, 1572864, 1835008, 2097152, 2621440, 3145728, 3670016, 4194304, 5242880, 6291456, 7340032, 8388608, 10485760, 12582912, 14680064, 16777216]
    private final int[] pageIdx2sizeTab;
    // lookup table for sizeIdx <= smallMaxSizeIdx
    // 这个表格存的则是索引和对应的size的对应表格（其实就是上面表格中size那一列的数据）
    // [16, 32, 48, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 640, 768, 896, 1024, 1280, 1536, 1792, 2048, 2560, 3072, 3584, 4096, 5120, 6144, 7168, 8192, 10240, 12288, 14336, 16384, 20480, 24576, 28672, 32768, 40960, 49152, 57344, 65536, 81920, 98304, 114688, 131072, 163840, 196608, 229376, 262144, 327680, 393216, 458752, 524288, 655360, 786432, 917504, 1048576, 1310720, 1572864, 1835008, 2097152, 2621440, 3145728, 3670016, 4194304, 5242880, 6291456, 7340032, 8388608, 10485760, 12582912, 14680064, 16777216]
    private final int[] sizeIdx2sizeTab;
    // lookup table used for size <= lookupMaxclass
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxclass >> LOG2_QUANTUM
    // 这个表格主要存储的是lookup下的size以每2B为单位存储一个size到其对应的size的缓存值(主要是对小数据类型的对应的idx的查找进行缓存)

    // size2idxTab是针对小规格内存大小的，存的元素是 指定规格大小在sizeClasses的索引值
    // [0,1,2,3,4,5,6,7,8,8,9,9,10,10,11,11,12,12,12,12,13,13,13,13,14,14,14,14,15,15,15,15,16,16,16,16,16,16,16,16,17,17,17,17,17,17,17,17,18,18,18,18,18,18,18,18,19,19,19,19,19,19,19,19,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,21,21,21,21,21,21,21,21,21,21,21,21,21,21,21,21,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,23,23,23,23,23,23,23,23,23,23,23,23,23,23,23,23,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,25,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,26,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27,27]
    private final int[] size2idxTab;

    /**
     * 主要是生成 {@link #sizeClasses} 这个数组
     * 下面的代码其实就是以1<<(LOG2_QUANTUM)为一组，这一组的log2Group和log2Delta是相同，并增长这个nDelta来使得每组数据的两个size的差值相同。
     *
     * 返回值是 数组真实的大小
     */
    private int sizeClasses() {
        int normalMaxSize = -1;
        int index = 0;
        int size = 0;
        int log2Group = LOG2_QUANTUM;   // 初始值4
        int log2Delta = LOG2_QUANTUM;   // 初始值4
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP;   // 表示的是每一组的数量  4

        //First small group, nDelta start at 0.
        //first size class is 1 << LOG2_QUANTUM 1<<4=16
        // 第一组从 1 << LOG2_QUANTUM 开始,从 1 << (LOG2_QUANTUM + 1) 结束
        // 第一个小组 nDelta从0开始
        int nDelta = 0;
        while (nDelta < ndeltaLimit) { // 此处循环四次 ndeltaLimit 初始值是4，nDelta初始值是0
            //   (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
            //   ( 0,     4,        4,         0,       no,             yes,        4)
            //   ( 1,     4,        4,         1,       no,             yes,        4)
            //   ( 2,     4,        4,         2,       no,             yes,        4)
            //   ( 3,     4,        4,         3,       no,             yes,        4)
            size = sizeClass(index++, log2Group, log2Delta, nDelta++);
        }
        // 走到此处index=4，nDelta=4

        // 后续组中 log2Group 比 log2Delta 大 LOG2_SIZE_CLASS_GROUP (4) ,并且 nDelta 会从1走到1 << LOG2_SIZE_CLASS_GROUP (4)
        // 这则可以保证size = (1 << log2Group) + (1 << log2Delta) * nDelta 在 nDelta=1 << LOG2_SIZE_CLASS_GROUP (4) 是 size=1<<(1+log2Group)
        log2Group += LOG2_SIZE_CLASS_GROUP; // +2操作

        //All remaining groups, nDelta start at 1.
        // 剩余的group nDelta从1开始
        while (size < chunkSize) {
            nDelta = 1;

            // 4个作为一组
            while (nDelta <= ndeltaLimit && size < chunkSize) {
                size = sizeClass(index++, log2Group, log2Delta, nDelta++);
                normalMaxSize = size;
            }

            log2Group++;
            log2Delta++;
        }

        //chunkSize must be normalMaxSize
        assert chunkSize == normalMaxSize;

        //return number of size index
        return index;
    }

    /**
     * calculate size class
     *
     * @param index
     * @param log2Group
     * @param log2Delta
     * @param nDelta
     * @return
     */
    // 第一组 分别是 index log2Group log2Delta nDelta --> isMultiPageSize
    //              0     4         4         0          no
    //              1     4         4         1          no
    //              2     4         4         2          no
    //              3     4         4         3          no
    private int sizeClass(int index, int log2Group, int log2Delta, int nDelta) {
        short isMultiPageSize;
        //log2Delta大于pageShifts则表示size的计算的最小单位都大于pageSize
        if (log2Delta >= pageShifts) {
            isMultiPageSize = yes;
        } else {
            // 页面容量 8k
            int pageSize = 1 << pageShifts;
            // 公式计算size
            // 1<<4 + 1<<4*0 = 16
            // 1<<4 + 1<<4*1 = 32
            // 1<<4 + 1<<4*2 = 48
            // 1<<4 + 1<<4*3 = 64
            int size = (1 << log2Group) + (1 << log2Delta) * nDelta;
            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
        }

        // 0 1 1 2
        int log2Ndelta = nDelta == 0 ? 0 : log2(nDelta);
        // no no no no
        byte remove = 1 << log2Ndelta < nDelta? yes : no;
        // 2^(log2Delta+log2Ndelta) 即为(1 << log2Delta) * nDelta,
        // 故log2Delta + log2Ndelta == log2Group是size=2^(log2Group + 1)
        // 4 + 0 == 4 ? 5 : 4 = 5
        // 4 + 1 == 5 ? 5 : 4 = 5
        // 4 + 1 == 5 ? 5 : 4 = 5
        // 4 + 2 == 5 ? 5 : 4 = 4
        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group;
        if (log2Size == log2Group) {
            remove = yes;
        }

        // log2Size < 13 + 2 ? yes : no
        short isSubpage = log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP? yes : no;
        // log2DeltaLookup在LOG2_MAX_LOOKUP_SIZE之前是log2Delta,之后是no 12
        // loge2Size < 12 || log2Size == 12 && remove == no ? log2Delta : no
        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no
                ? log2Delta : no;

        short[] sz = {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };

        sizeClasses[index] = sz;
        int size = (1 << log2Group) + (nDelta << log2Delta);

        // 计算是pageSize的数量
        if (sz[PAGESIZE_IDX] == yes) {
            nPSizes++;
        }
        // 计算是subPage的数量
        if (sz[SUBPAGE_IDX] == yes) {
            nSubpages++;
            // 这个值是用来判断分配是从subpage分配还是直接从poolchunk中进行分配
            smallMaxSizeIdx = index;
        }
        // 计算lookupMaxSize
        if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
            lookupMaxSize = size;
        }
        return size;
    }

    /**
     * 初始化数组数据
     *
     * @param sizeIdx2sizeTab
     * @param pageIdx2sizeTab
     */
    private void idx2SizeTab(int[] sizeIdx2sizeTab, int[] pageIdx2sizeTab) {
        int pageIdx = 0;

        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            int log2Group = sizeClass[LOG2GROUP_IDX];
            int log2Delta = sizeClass[LOG2DELTA_IDX];
            int nDelta = sizeClass[NDELTA_IDX];

            int size = (1 << log2Group) + (nDelta << log2Delta);
            sizeIdx2sizeTab[i] = size;

            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = size;
            }
        }
    }

    private void size2idxTab(int[] size2idxTab) {
        int idx = 0;
        int size = 0;

        for (int i = 0; size <= lookupMaxSize; i++) {
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            int times = 1 << log2Delta - LOG2_QUANTUM;

            while (size <= lookupMaxSize && times-- > 0) {
                size2idxTab[idx++] = i;
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int lgDelta = shift + LOG2_QUANTUM - 1;
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int log2Delta = shift + pageShifts - 1;
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }

    /**
     * 根据业务需要的内存大小 转换为netty能够分配的最接近的一个规格的内存大小sizeClasses上的索引
     *
     * @param size request size 业务需要的内存的大小
     * @return 返回netty的合适规格大小在sizeClasses上的索引
     */
    @Override
    public int size2SizeIdx(int size) {
        if (size == 0) {
            return 0;
        }
        // 假如业务需要的内存大于 chunkSize 则返回最大的索引
        if (size > chunkSize) {
            return nSizes;
        }

        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        // 假如业务需要的内存小于等于4k
        if (size <= lookupMaxSize) { // lookupMaxSize 默认4096
            //size-1 / MIN_TINY  左移4位，就是除以16
            // eg. size = 16 则 size - 1 = 15 ，故 15 >> 4 = 0，所以 size2idxTab[0] = 0 在sizeClasses的0位置处的大小是16
            // eg. size = 258 则 size - 1 = 257 ，故 257 >> 4 = 16，所以 size2idxTab[16] = 12 在sizeClasses的0位置处的大小是320
            // eg. size = 2345 则 size - 1 = 2344 ，故 2344 >> 4 = 146，所以 size2idxTab[146] = 24 在sizeClasses的0位置处的大小是2.5K
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }
        // 走到此处 前置条件 size > lookupMaxSize

        // 对申请内存大小进行log2的向上取整，就是每组最后一个内存块size。-1是为了避免申请内存大小刚好等于2的指数次幂时被翻倍。
        // 将log2Group = log2Delta + LOG2_SIZE_CLASS_GROUP，nDelta=2^LOG2_SIZE_CLASS_GROUP代入计算公式，可得
        //         lastSize = 1 << (log2Group + 1)
        // 即x = log2Group + 1
        // eg. size = 5000 此时 x = log2(9999) = 13
        int x = log2((size << 1) - 1);
        // LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1 是7
        // 13 < 2 + 4 + 1 ? 0 : 13 - (2 + 4) = 7

        // ===== shift表示当前size在第几组，group表示该组第一个元素在sizeClasses上的索引=====

        // shift表示当前在第几组，每四个一组，也就是sizeClasses表格中index 0-3位第0组，4-7为第1组...
        // 条件x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1，也就是log2Group < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM
        // 满足这个条件的只有第0组的size，此时shift是0
        // 从sizeClasses方法可以看到，除了第0组，都满足shift = log2Group - LOG2_QUANTUM - (LOG2_SIZE_CLASS_GROUP - 1)
        // 也就是 shift = log2Group - 5
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1 ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM);
        // shift << LOG2_SIZE_CLASS_GROUP就是该组第一个内存块size的索引
        // 左移2位  7* 4 = 28
        int group = shift << LOG2_SIZE_CLASS_GROUP;

        // ===== 计算log2Delta
        // 第0组固定是LOG2_QUANTUM
        // 除了第0组，将nDelta = 2^LOG2_SIZE_CLASS_GROUP代入计算公式
        // lastSize = ( 2^LOG2_SIZE_CLASS_GROUP + 2^LOG2_SIZE_CLASS_GROUP ) * (1 << log2Delta)
        // lastSize = (1 << log2Delta) << LOG2_SIZE_CLASS_GROUP << 1

        // 13 < 2 + 4 + 1 ? 4 : 13 - 2 - 1 = 10
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1 ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;

        // 前面已经定位到第几组了，下面要找到申请内存大小应分配在该组第几位
        // -1024
        int deltaInverseMask = -1 << log2Delta;
        // 4999 & -1024
        // 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001 0011 1000 0111
        // 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1100 0000 0000
        // 4096 >> 10
        // (4999 & -1024) >> 10 & (1<<2)-1 = 0
        // & deltaInverseMask，将申请内存大小最后log2Delta个bit位设置为0
        // >> log2Delta，右移log2Delta个bit位，就是除以(1 << log2Delta)，结果就是(nDelta - 1 + 2 ^ LOG2_SIZE_CLASS_GROUP)
        // & (1 << LOG2_SIZE_CLASS_GROUP) - 1， 取模运算
        // size - 1，是为了申请内存等于内存块size时避免分配到下一个内存块size中，即n == (1 << log2Delta)的场景
        int mod = (size - 1 & deltaInverseMask) >> log2Delta & (1 << LOG2_SIZE_CLASS_GROUP) - 1;
        return group + mod;
    }

    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }

    @Override
    public int pages2pageIdxFloor(int pages) {
        return pages2pageIdxCompute(pages, true);
    }

    /**
     * 根据给定的页数来计算出其对应的页的序号，其获取的则是下面表格中最后一列的数值，即为isMultiPageSize是1时从0开始的对size的编号
     * size = 1 << log2Group + nDelta * (1 << log2Delta)
     * log2Group = log2Delta + LOG2_SIZE_CLASS_GROUP
     *
     * 要使 size = k(1 << pageShifts)
     *
     * k = (nDelta + 2^LOG2_SIZE_CLASS_GROUP) * 2^(log2Delta - pageShifts)
     *
     * @param pages 页面大小的倍数
     * @param floor
     * @return 返回页面大小倍数的索引
     */
    private int pages2pageIdxCompute(int pages, boolean floor) {
        // 默认13位 也就是乘以8k
        int pageSize = pages << pageShifts;
        if (pageSize > chunkSize) {
            return nPSizes;
        }

        // 对pageSize进行log2的向上取整 23
        // x = log2Group+1
        int x = log2((pageSize << 1) - 1);

        // shift表示第几组，group表示该组在sizeClasses上index索引位置

        // x >= LOG2_SIZE_CLASS_GROUP + pageShifts + 1 后则每个size都是 1 << pageShifts的倍数
        // 小于15视为0 大于等于15其
        // 23 < 15 ? 0 : 23-15=8
        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);
        // 8<<2 = 32
        int group = shift << LOG2_SIZE_CLASS_GROUP;
        // 23 < 15+1?13:23-2-1=20
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1? pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (pageSize - 1 & deltaInverseMask) >> log2Delta & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int pageIdx = group + mod;

        if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            pageIdx--;
        }

        return pageIdx;
    }

    // Round size up to the nearest multiple of alignment.
    private int alignSize(int size) {
        int delta = size & directMemoryCacheAlignment - 1;
        return delta == 0? size : size + directMemoryCacheAlignment - delta;
    }

    @Override
    public int normalizeSize(int size) {
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            assert ret == normalizeSizeCompute(size);
            return ret;
        }
        return normalizeSizeCompute(size);
    }

    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        int delta = 1 << log2Delta;
        int delta_mask = delta - 1;
        return size + delta_mask & ~delta_mask;
    }
}
