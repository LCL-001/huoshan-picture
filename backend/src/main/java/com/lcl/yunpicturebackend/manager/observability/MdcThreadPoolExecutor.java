package com.lcl.yunpicturebackend.manager.observability;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 提交任务时快照 MDC、在工作线程里恢复，保证异步日志仍然带得上 traceId。
 * <p>
 * 注意必须在 {@link #execute(Runnable)}（调用方线程）阶段捕获上下文：
 * 若放在 beforeExecute 里取，拿到的已经是工作线程自己的上下文，提交方的 traceId 已经丢了。
 * submit() 内部同样走 execute()，因此这一处覆盖所有提交方式。
 */
public class MdcThreadPoolExecutor extends ThreadPoolExecutor {

    public MdcThreadPoolExecutor(int corePoolSize,
                                 int maximumPoolSize,
                                 long keepAliveTime,
                                 TimeUnit unit,
                                 BlockingQueue<Runnable> workQueue,
                                 ThreadFactory threadFactory,
                                 RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    @Override
    public void execute(Runnable command) {
        Map<String, String> context = TraceContext.snapshot();
        super.execute(context == null ? command : wrap(command, context));
    }

    private static Runnable wrap(Runnable task, Map<String, String> context) {
        return () -> {
            Map<String, String> previous = TraceContext.snapshot();
            TraceContext.restore(context);
            try {
                task.run();
            } finally {
                // 线程会被复用，跑完还原成进入前的状态
                TraceContext.restore(previous);
            }
        };
    }
}
