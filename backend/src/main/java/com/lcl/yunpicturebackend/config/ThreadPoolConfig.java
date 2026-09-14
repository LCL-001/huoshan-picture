package com.lcl.yunpicturebackend.config;

import com.lcl.yunpicturebackend.manager.observability.MdcThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration
@Slf4j
public class ThreadPoolConfig {

    @Bean(name = "pictureUploadExecutor")
    public ExecutorService pictureUploadExecutor() {
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        int maxPoolSize = corePoolSize * 2;
        long keepAliveTime = 60L;
        
        // 用带 MDC 透传的实现：异步抓取/上传的日志同样能带上请求的 traceId
        MdcThreadPoolExecutor executor = new MdcThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setName("picture-upload-" + (++count));
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        log.info("图片上传线程池初始化完成，核心线程数: {}, 最大线程数: {}", 
                corePoolSize, maxPoolSize);
        
        return executor;
    }

    @Bean(name = "aiAssistantExecutor")
    public ExecutorService aiAssistantExecutor() {
        int maxPoolSize = 64;
        // SSE 转发线程按一次对话的时长占用（最长 300s），必须与上传/异步等短任务线程池隔离，
        // 否则几条长对话就能把 async-task- / picture-upload- 的连接池饿死。
        // 用 SynchronousQueue + 上界：不排队，超限直接拒绝（调用方转成响亮的失败），避免长任务静默堆积。
        MdcThreadPoolExecutor executor = new MdcThreadPoolExecutor(
                0,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setName("ai-assistant-" + (++count));
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        log.info("AI 助手转发线程池初始化完成，最大线程数: {}", maxPoolSize);

        return executor;
    }
}
