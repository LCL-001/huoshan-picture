package com.lcl.yunpicturebackend.manager.websocket.disruptor;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.dsl.Disruptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

/**
 * 图片编辑事件 disruptor 配置
 */
@Slf4j
@Configuration
public class PictureEditEventDisruptorConfig {

    @Resource
    private PictureEditEventWorkHandler pictureEditEventWorkHandler;

    @Bean("pictureEditEventDisruptor")
    public Disruptor<PictureEditEvent> messageModelRingBuffer() {
        // ringBuffer 的大小
        int bufferSize = 1024 * 256;
        Disruptor<PictureEditEvent> disruptor = new Disruptor<>(
                PictureEditEvent::new,
                bufferSize,
                ThreadFactoryBuilder.create().setNamePrefix("pictureEditEventDisruptor").build()
        );
        // 兜底异常处理：默认实现（FatalExceptionHandler）会终止消费线程，导致之后所有协作编辑消息静默丢失
        disruptor.setDefaultExceptionHandler(new ExceptionHandler<PictureEditEvent>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, PictureEditEvent event) {
                log.error("图片编辑事件处理异常, sequence: {}, pictureId: {}", sequence,
                        event != null ? event.getPictureId() : null, ex);
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                log.error("图片编辑事件消费者启动异常", ex);
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                log.error("图片编辑事件消费者关闭异常", ex);
            }
        });
        // 设置消费者
        disruptor.handleEventsWithWorkerPool(pictureEditEventWorkHandler);
        // 开启 disruptor
        disruptor.start();
        return disruptor;
    }
}
