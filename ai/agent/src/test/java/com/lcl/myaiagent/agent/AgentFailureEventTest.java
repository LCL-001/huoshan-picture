package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.event.AgentEvent;
import com.lcl.myaiagent.agent.event.RecordingAgentEventListener;
import com.lcl.myaiagent.agent.model.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败与超时的收尾契约（T8-c，规则 12 先红后绿）。
 * <p>
 * 目标行为：provider 超时或调用失败时，本轮运行**只产出一条 {@link AgentEvent.Error} 并立即终止**
 * （不再把原始异常文本当回答发出去，也不再因为循环不终止而重复 2-3 轮）。
 * <p>
 * 两种超时形态取自 T6.1 的实测（docs/decisions.md 2026-09-14 R3 行与 T6.1 行）：
 * ① 流空闲超时——状态码 200 的 {@link WebClientResponseException} 沿 cause 链包着
 * {@link TimeoutException}；② 连接/首包超时——{@link WebClientRequestException} 包着
 * {@link HttpTimeoutException}。
 * <p>
 * 分类只看 cause 链（外层类型由框架决定，随版本变化），所以这里用可构造的等价包装承载这两种链路。
 */
@DisplayName("失败与超时的事件收尾")
class AgentFailureEventTest {

    /** 固定文案：超时与一般失败各一句，原文只进日志 */
    private static final String TIMEOUT_TEXT = "助手响应超时，请重试";
    private static final String FAILURE_TEXT = "助手处理失败，请稍后重试";

    /** 可数步数的图库助手：真实 agent + 桩模型，用来证明"失败即终止" */
    private static final class CountingAssistantAgent extends HuoshanAssistantAgent {

        private int calls;

        CountingAssistantAgent(ChatModel model) {
            super(new ToolCallback[0], model, "failure-contract", new NoopChatMemory());
        }

        int stepCalls() {
            return calls;
        }

        @Override
        public List<AgentEvent> step() {
            calls++;
            return super.step();
        }
    }

    private static RecordingAgentEventListener run(ChatModel model) {
        CountingAssistantAgent agent = new CountingAssistantAgent(model);
        RecordingAgentEventListener listener = new RecordingAgentEventListener();
        agent.runLoop("帮我看一下我的空间", listener);
        return listener;
    }

    // ---------------- 超时形态 ----------------

    @Test
    @DisplayName("流空闲超时：一条超时 error + Done，运行终止")
    void idleTimeoutEndsRunWithSingleErrorEvent() {
        Throwable idleTimeout = new TimeoutException("响应流空闲超时");
        // R3 实测的外层是 WebClientResponseException（状态码 200）；它的公开构造器不接受 cause，
        // 这里保留"外层异常 → TimeoutException"的链路形态（分类沿 cause 链走，与真实链路等价）
        RuntimeException failure = new IllegalStateException(
                "200 OK from POST https://api.example.com/v1/chat/completions, but response failed", idleTimeout);

        RecordingAgentEventListener listener = run(new ThrowingChatModel(failure));

        assertThat(errorTexts(listener)).containsExactly(TIMEOUT_TEXT);
        assertThat(listener.count(AgentEvent.Done.class)).isEqualTo(1);
        assertThat(listener.events().get(listener.events().size() - 1)).isInstanceOf(AgentEvent.Done.class);
    }

    @Test
    @DisplayName("连接超时（HttpTimeoutException 形态）：同样只发超时 error")
    void connectTimeoutEndsRunWithSingleErrorEvent() {
        RuntimeException failure = new WebClientRequestException(
                new HttpTimeoutException("connect timed out"),
                HttpMethod.POST, URI.create("https://api.example.com/v1/chat/completions"), HttpHeaders.EMPTY);

        RecordingAgentEventListener listener = run(new ThrowingChatModel(failure));

        assertThat(errorTexts(listener)).containsExactly(TIMEOUT_TEXT);
    }

    // ---------------- 一般失败 ----------------

    @Test
    @DisplayName("一般调用失败：一条通用 error，且不被误判为超时")
    void genericFailureEndsRunWithSingleErrorEvent() {
        RuntimeException failure = WebClientResponseException.create(
                500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

        RecordingAgentEventListener listener = run(new ThrowingChatModel(failure));

        assertThat(errorTexts(listener)).containsExactly(FAILURE_TEXT);
    }

    // ---------------- 收尾语义 ----------------

    @Test
    @DisplayName("失败即终止：只走一步，不触发卡死判定，也不再有回答帧")
    void failureTerminatesTheRun() {
        CountingAssistantAgent agent = new CountingAssistantAgent(
                new ThrowingChatModel(new RuntimeException("boom from provider")));
        RecordingAgentEventListener listener = new RecordingAgentEventListener();

        agent.runLoop("帮我看一下我的空间", listener);

        assertThat(agent.stepCalls()).as("失败当轮终止，不再重试到卡死").isEqualTo(1);
        assertThat(listener.count(AgentEvent.Answer.class)).isZero();
        assertThat(listener.joinedAnswers()).doesNotContain("检测到循环");
    }

    @Test
    @DisplayName("原始异常文本不进事件流（只在日志里），messageList 也不记录异常")
    void originalExceptionTextNeverReachesTheEvents() {
        CountingAssistantAgent agent = new CountingAssistantAgent(
                new ThrowingChatModel(new RuntimeException("SECRET-PROVIDER-DETAIL")));
        RecordingAgentEventListener listener = new RecordingAgentEventListener();

        agent.runLoop("帮我看一下我的空间", listener);

        assertThat(listener.events().stream()
                .filter(AgentEvent.Error.class::isInstance)
                .map(event -> ((AgentEvent.Error) event).content()))
                .allSatisfy(text -> assertThat(text).doesNotContain("SECRET-PROVIDER-DETAIL"));
        assertThat(agent.getMessageList())
                .noneSatisfy(message -> assertThat(String.valueOf(message.getText()))
                        .contains("SECRET-PROVIDER-DETAIL"));
    }

    @Test
    @DisplayName("循环兜底：step() 抛非预期异常 → 一条通用 error + Done，状态停在 ERROR")
    void unexpectedStepFailureIsHandledByTheLoop() {
        ThrowingStepAgent agent = new ThrowingStepAgent();
        RecordingAgentEventListener listener = new RecordingAgentEventListener();

        agent.runLoop("问一句", listener);

        assertThat(errorTexts(listener)).containsExactly(FAILURE_TEXT);
        assertThat(listener.count(AgentEvent.Done.class)).isEqualTo(1);
        assertThat(agent.getState()).isEqualTo(AgentState.ERROR);
    }

    private static List<String> errorTexts(RecordingAgentEventListener listener) {
        return listener.events().stream()
                .filter(AgentEvent.Error.class::isInstance)
                .map(event -> ((AgentEvent.Error) event).content())
                .toList();
    }

    /** step() 直接抛异常的 agent：异常绕过 think/act，验证循环的兜底收尾 */
    private static final class ThrowingStepAgent extends BaseAgent {

        @Override
        public List<AgentEvent> step() {
            throw new IllegalStateException("unexpected internal failure");
        }

        @Override
        protected void cleanUp() {
            setCurrentStep(0);
            if (getState() != AgentState.ERROR) {
                setState(AgentState.IDLE);
            }
        }
    }

    /** 抛固定异常的 ChatModel 桩：模拟 provider 侧失败，不连网 */    private static final class ThrowingChatModel implements ChatModel {

        private final RuntimeException failure;

        ThrowingChatModel(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw failure;
        }
    }

    /** 记忆桩：本用例只关心失败收尾，不需要真记忆 */
    private static final class NoopChatMemory implements ChatMemory {

        @Override
        public void add(String conversationId, List<Message> messages) {
            // 无需持久化
        }

        @Override
        public List<Message> get(String conversationId) {
            return Collections.emptyList();
        }

        @Override
        public void clear(String conversationId) {
            // 无状态
        }
    }
}
