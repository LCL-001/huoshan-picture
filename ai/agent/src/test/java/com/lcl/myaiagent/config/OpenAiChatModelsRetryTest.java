package com.lcl.myaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 重试时限单测（docs/plan.md T18）：钉住"LLM 调用必须有界"这一条。
 * <p>
 * 起点是实测出来的一个不相容：Spring AI 的 {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} 是
 * {@code maxAttempts(10)} + 指数退避 2s→180s（9 段退避合计约 19 分钟），而这条链路的上限是
 * SSE emitter 的 300s —— "provider 不可达"时用户会先被裸断连，error 事件根本没机会发出（T8-d 实测）。
 * 这里四个断言各管一件事：**生产参数确实有界**、**总预算真的会截断重试**、**不可重试的错误不白等**、
 * **两个模型都挂上了它**（不挂就落回默认模板 = 19 分钟）。
 * </p>
 */
class OpenAiChatModelsRetryTest {

    /**
     * 生产口径：固定用真实常量跑一次"秒失败"，应当恰好 4 次尝试就放弃。
     * <p>
     * 退避是 0.5/1/2s，所以本条要真实等约 3.5s —— 换来的是"生产常量没被改坏"这个断言不靠读代码。
     * </p>
     */
    @Test
    void givesUpAfterFourFastFailuresAndNeverApproachesTheDefaultBudget() {
        RetryTemplate template = OpenAiChatModels.boundedRetryTemplate();
        AtomicInteger attempts = new AtomicInteger();
        long start = System.nanoTime();

        assertThatThrownBy(() -> template.execute(context -> {
            attempts.incrementAndGet();
            throw new ResourceAccessException("provider 连不上");
        })).isInstanceOf(ResourceAccessException.class);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(attempts.get())
                .as("默认模板会试 10 次（约 19 分钟）；有界模板必须 4 次就放弃")
                .isEqualTo(OpenAiChatModels.RETRY_MAX_ATTEMPTS)
                .isEqualTo(4);
        assertThat(OpenAiChatModels.RETRY_BUDGET.toSeconds())
                .as("总预算必须明显小于 SSE emitter 的 300s，否则失败照样看不见")
                .isLessThan(300L);
        assertThat(elapsed)
                .as("三次退避 0.5/1/2s，不该接近 45s 预算")
                .isLessThan(Duration.ofSeconds(15));
    }

    /**
     * 总预算真的会截断，而且截断它的是**时间**不是次数：尝试上限放到 10、预算 800ms、退避 500ms→1s
     * ⇒ 只跑 2 次就停（若靠次数上限，会跑满 10 次）。
     * <p>
     * 实测口径（本条先红后改的产物）：`RetryTemplate` 是在**退避之后**才咨询策略的，所以预算的
     * 实际效果是"最多多花一次退避"——生产值下即 45s + 至多 4s，不会失控。预算若小于首次退避，
     * 结果是一次都不重试（只白睡一次退避）。
     * </p>
     */
    @Test
    void totalBudgetTruncatesRetriesEvenWhenAttemptsRemain() {
        RetryTemplate template = OpenAiChatModels.boundedRetryTemplate(
                Duration.ofMillis(800), 10, Duration.ofMillis(500), Duration.ofSeconds(4));
        AtomicInteger attempts = new AtomicInteger();
        long start = System.nanoTime();

        assertThatThrownBy(() -> template.execute(context -> {
            attempts.incrementAndGet();
            throw new ResourceAccessException("provider 连不上");
        })).isInstanceOf(ResourceAccessException.class);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(attempts.get())
                .as("800ms 预算下：首次 + 一次重试（第二次退避时已过期），而不是 10 次")
                .isEqualTo(2);
        assertThat(elapsed)
                .as("两次退避合计约 1.5s，远小于 10 次尝试会花的十几分钟")
                .isLessThan(Duration.ofSeconds(6));
    }

    /** 不在可重试集合里的错误（如请求本身非法）只试一次：不该白等三倍时间 */
    @Test
    void doesNotRetryErrorsOutsideTheRetryableSet() {
        RetryTemplate template = OpenAiChatModels.boundedRetryTemplate(
                Duration.ofMillis(300), 10, Duration.ofMillis(500), Duration.ofSeconds(4));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> template.execute(context -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("请求体非法");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    /** 两个模型都必须挂上有界模板：漏挂一个，那条链路就回到"19 分钟才失败、用户被裸断连" */
    @Test
    void bothModelsCarryTheBoundedTemplateInsteadOfTheFrameworkDefault() throws Exception {
        OpenAiChatModels models = OpenAiChatModels.from(configuredProperties());

        RetryTemplate assistantTemplate = retryTemplateOf(models.assistant());
        RetryTemplate visionTemplate = retryTemplateOf(models.vision());

        assertThat(assistantTemplate).as("主脑模型").isNotSameAs(RetryUtils.DEFAULT_RETRY_TEMPLATE);
        assertThat(visionTemplate).as("看图模型").isNotSameAs(RetryUtils.DEFAULT_RETRY_TEMPLATE);

        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> assistantTemplate.execute(context -> {
            attempts.incrementAndGet();
            throw new ResourceAccessException("provider 连不上");
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(attempts.get())
                .as("挂上的必须是有界模板（%d 次），不是框架默认的 10 次", OpenAiChatModels.RETRY_MAX_ATTEMPTS)
                .isEqualTo(4);
    }

    private static OpenAiModelProperties configuredProperties() {
        OpenAiModelProperties properties = new OpenAiModelProperties();
        for (OpenAiModelProperties.ModelEntry entry : List.of(properties.getAssistant(), properties.getVision())) {
            entry.setBaseUrl("http://127.0.0.1:1");
            entry.setApiKey("test-key");
            entry.setModel("test-model");
        }
        return properties;
    }

    /** 模型上挂的 RetryTemplate（私有字段；框架内部结构若变化会在这里响亮失败） */
    private static RetryTemplate retryTemplateOf(ChatModel model) throws Exception {
        return (RetryTemplate) readField(model, "retryTemplate");
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 字段可能在父类上，继续往上找
            }
        }
        throw new IllegalStateException("找不到字段 " + name + "：" + target.getClass().getName()
                + "（框架内部结构可能已变，本测试的反射路径需要同步更新）");
    }
}
