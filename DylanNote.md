# Netty 整体笔记架构
## 讲解ByteBuf源码分析
ByteBuf分类，作用

- ByteBuf接口


## Future和Promise源码分析

## EventExecutor和EventLoop接口分析

这几个都是JDK的接口
- Executor 接口
- ExecutorService  接口
- ScheduledExecutorService 接口

接下来是 Netty 的接口
- EventExecutorGroup 接口
- EventExecutor 接口
- EventLoopGroup 接口
- EventLoop 接口
   
## EventExecutor和EventLoop的抽象基类 
- AbstractEventExecutor
    - AbstractScheduledEventExecutor
  
定时器相关

- PriorityQueue 接口
- PriorityQueueNode 接口
- ScheduledFutureTask
- DefaultPriorityQueue 优先队列
- AbstractScheduledEventExecutor 抽象类

===========

- SingleThreadEventExecutor
    - DefaultEventExecutor
    - NioEventLoop
- ThreadExecutorMap

=====
- EventExecutorGroup
- AbstractEventExecutorGroup
- MultithreadEventExecutorGroup
- EventExecutorChooser
- MultithreadEventLoopGroup
- NioEventLoopGroup
- NIoEventLoop

DefaultEventLoopGroup 默认的

## Channel
- Channel 接口
- ChannelInboundHandler 接口和 ChannelInboundInvoker 接口
- ChannelOutboundHandler 接口和 ChannelOutboundInvoker 接口
- ChannelHandlerAdapter
    - ChannelInboundHandlerAdapter
    - ChannelOutboundHandlerAdapter
    - ChannelDuplexHandler
- ChannelHandlerMask类

- ChannelPipeline 接口
- DefaultChannelPipeline
- ChannelHandlerContext 接口
  - WriteTask
  - HeadContext
  - TailContext
  - PendingHandlerAddedTask
  - PendingHandlerRemovedTask
  
- AbstractChannel 抽象通道
- ChannelOutboundBuffer 写缓冲区
- AbstractNioChannel
  - AbstractNioByteChannel
  - AbstractNioMessageChannel

- NioSocketChannel
- NioServerSocketChannel

## 对象池
- Recycler
- ObjectPool




https://www.jianshu.com/p/cf57f20b4cb9
> https://www.jianshu.com/p/6c25b6386326