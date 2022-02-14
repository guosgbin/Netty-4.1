# Netty 整体笔记架构
## 讲解ByteBuf源码分析 TODO
ByteBuf分类，作用

- ByteBuf接口


## Future和Promise源码分析 TODO

## NioEventLoopGroup的体系 OK
前提条件 讲一下 线程工厂类 和 EventLoop 选择器类

1. 首先放一张继承体系图，分别讲解各个父类的作用
2. 讲解NioEventLoopGroup的初始化操作

## NIoEventLoop的体系



## EventExecutor和EventLoop接口分析

这几个都是JDK的接口
- Executor 接口
- ExecutorService  接口
- ScheduledExecutorService 接口

接下来是 Netty 的接口
- EventExecutorGroup 接口 OK
- EventExecutor 接口 OK
- EventLoopGroup 接口 OK
- EventLoop 接口 OK
   
## EventExecutor和EventLoop的抽象基类 
- AbstractEventExecutor OK
    - AbstractScheduledEventExecutor OK
  
定时器相关

- PriorityQueue 接口 OK
- PriorityQueueNode 接口 OK
- ScheduledFutureTask OK
- DefaultPriorityQueue 优先队列 OK
- AbstractScheduledEventExecutor 抽象类 OK

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

## 编解码器

将字节解码为消息 - ByteToMessageDecoder 和 ReplayingDecoder（ReplayingDecoderByteBuf）

将一种消息类型解码为另一种 - MessageToMessageDecoder



https://www.jianshu.com/p/cf57f20b4cb9
> https://www.jianshu.com/p/6c25b6386326