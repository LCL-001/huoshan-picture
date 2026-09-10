package com.lcl.yunpicturebackend.config;

import com.lcl.yunpicturebackend.manager.observability.MdcTaskDecorator;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @Async 执行器配置：显式定义线程池并透传 MDC。
 * <p>
 * 不配置的话 @Async 走默认执行器，线程名不可控、拒绝策略不可控，且异步日志会丢 traceId。
 * 线程池参数与图片上传线程池保持一致（核心 = CPU，最大 = 2×CPU，队列 100，CallerRunsPolicy）。
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-task-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
