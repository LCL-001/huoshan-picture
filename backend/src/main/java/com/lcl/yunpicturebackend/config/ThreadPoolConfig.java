package com.lcl.yunpicturebackend.config;

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
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
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
}
