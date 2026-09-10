package com.lcl.yunpicturebackend.manager.observability;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Spring TaskExecutor（@Async）的 MDC 透传装饰器。
 * 与 {@link MdcThreadPoolExecutor} 同样的语义：提交时快照、执行时恢复、结束后还原。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = TraceContext.snapshot();
        return () -> {
            Map<String, String> previous = TraceContext.snapshot();
            TraceContext.restore(context);
            try {
                runnable.run();
            } finally {
                TraceContext.restore(previous);
            }
        };
    }
}
