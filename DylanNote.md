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
    
    https://www.jianshu.com/p/6c25b6386326