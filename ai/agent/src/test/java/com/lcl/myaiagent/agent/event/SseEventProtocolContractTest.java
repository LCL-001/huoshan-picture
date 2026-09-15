package com.lcl.myaiagent.agent.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.myaiagent.agent.BaseAgent;
import com.lcl.myaiagent.agent.model.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 事件协议的**显式契约**（T8-hard）：钉住帧的字段名、事件语义与"每条流恰好一个终止帧"。
 * <p>
 * 消费端三处，改协议必须同步改它们并让本测试保持绿：
 * ① 图库后端代理 {@code AiAssistantProxyManager.relay()}（按原样转发 data 载荷、认 {@code [DONE]}）；
 * ② 前端 {@code frontend/src/utils/assistantSse.ts}（按 {@code event} 字段分流，读 kind/name/content）；
 * ③ 本测试。
 * <p>
 * 与 {@code BaseAgentTest} 的分工：那边测状态机与循环语义，这边测**对外协议**——帧级（Map 经真实
 * 序列化路径变成 JSON 文本后逐字段断言）、循环级（事件序列与终止帧唯一性）、runStream 级（异步路径
 * 产出同样的帧）、断流。
 */
@DisplayName("SSE 事件协议契约")
class SseEventProtocolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON 帧：把记录到的 Map 经真实序列化路径转成 JSON 文本再解析，字段名断言才算钉住线上帧 */
    private static JsonNode jsonOf(RecordingSseEmitter emitter, int index) throws Exception {
        Object payload = emitter.payloads().get(index);
        assertThat(payload).as("第 %d 帧应是 JSON 事件帧（Map）", index).isInstanceOf(Map.class);
        return MAPPER.readTree(MAPPER.writeValueAsString(payload));
    }

    /**
     * 脚本化 BaseAgent：按脚本逐个消费 step 产出（脚本用完后重复最后一条），供循环级与 runStream 级断言。
     * {@code finishOnStep} = 消费到脚本最后一条时置 FINISHED（模拟"这一步就是最终回答"）。
     */
    static class ScriptedAgent extends BaseAgent {

        private final List<List<AgentEvent>> script;
        private final AtomicInteger stepCalls = new AtomicInteger();
        private boolean finishOnStep;
        private boolean stopOnStep;
        private Map<String, Object> summary;
        private RecordingSseEmitter emitter;

        ScriptedAgent(List<List<AgentEvent>> script) {
            this.script = script;
            setName("ScriptedAgent");
        }

        /** 覆写事件流载体，让 runStream 把帧写进记录型 emitter */
        void useEmitter(RecordingSseEmitter emitter) {
            this.emitter = emitter;
        }

        void setFinishOnStep(boolean finishOnStep) {
            this.finishOnStep = finishOnStep;
        }

        void setStopOnStep(boolean stopOnStep) {
            this.stopOnStep = stopOnStep;
        }

        void setSummary(Map<String, Object> summary) {
            this.summary = summary;
        }

        int stepCalls() {
            return stepCalls.get();
        }

        /** 同步驱动一次运行：protected runLoop 只能由子类自己调用（本类与断言代码不同包） */
        void runSync(String userPrompt, AgentEventListener listener) {
            runLoop(userPrompt, listener);
        }

        @Override
        public List<AgentEvent> step() {
            int index = Math.min(stepCalls.incrementAndGet() - 1, script.size() - 1);
            if (stopOnStep) {
                setStopped(true);
            }
            List<AgentEvent> events = script.isEmpty()
                    ? List.of(new AgentEvent.Answer("默认回答"))
                    : script.get(index);
            if (finishOnStep && index == script.size() - 1) {
                setState(AgentState.FINISHED);
            }
            return events;
        }

        @Override
        protected Map<String, Object> runSummary() {
            return summary;
        }

        @Override
        protected SseEmitter createEmitter() {
            return emitter != null ? emitter : super.createEmitter();
        }

        @Override
        protected void cleanUp() {
            setCurrentStep(0);
            if (getState() != AgentState.ERROR) {
                setState(AgentState.IDLE);
            }
        }
    }

    private static ScriptedAgent scripted(List<List<AgentEvent>> steps) {
        return new ScriptedAgent(steps);
    }

    /** 每步都回同一句话（不置 FINISHED，用于上限/停止/断流路径） */
    private static ScriptedAgent answeringAlways(String text) {
        return new ScriptedAgent(List.of(List.of(new AgentEvent.Answer(text))));
    }

    private static RecordingAgentEventListener run(ScriptedAgent agent, String prompt) {
        RecordingAgentEventListener listener = new RecordingAgentEventListener();
        agent.runSync(prompt, listener);
        return listener;
    }

    /** 不变量：恰好一个 Done，且它是最后一帧 */
    private static void assertSingleDoneLast(RecordingAgentEventListener listener) {
        assertThat(listener.count(AgentEvent.Done.class)).as("每条流恰好一个 Done").isEqualTo(1);
        List<AgentEvent> events = listener.events();
        assertThat(events.get(events.size() - 1)).as("Done 必须是最后一帧").isInstanceOf(AgentEvent.Done.class);
    }

    // ==================== 帧级 ====================

    @Nested
    @DisplayName("帧级：字段名与取值")
    class FrameLevel {

        private RecordingSseEmitter emit(AgentEvent... events) {
            RecordingSseEmitter emitter = new RecordingSseEmitter();
            SseAgentEventListener listener = new SseAgentEventListener(emitter, () -> {
            });
            for (AgentEvent event : events) {
                listener.onEvent(event);
            }
            return emitter;
        }

        @Test
        @DisplayName("step 帧：event/kind/name/content；思考与工具用 kind 区分")
        void stepFrameFields() throws Exception {
            RecordingSseEmitter emitter = emit(
                    AgentEvent.Step.think("我先看看"),
                    AgentEvent.Step.tool("listSpaces、listPictures", "工具结果"));

            JsonNode think = jsonOf(emitter, 0);
            assertThat(think.get("event").asText()).isEqualTo("step");
            assertThat(think.get("kind").asText()).isEqualTo("think");
            assertThat(think.get("name").asText()).isEqualTo("思考");
            assertThat(think.get("content").asText()).isEqualTo("我先看看");

            JsonNode tool = jsonOf(emitter, 1);
            assertThat(tool.get("event").asText()).isEqualTo("step");
            assertThat(tool.get("kind").asText()).isEqualTo("tool");
            assertThat(tool.get("name").asText()).isEqualTo("listSpaces、listPictures");
            assertThat(tool.get("content").asText()).isEqualTo("工具结果");
        }

        @Test
        @DisplayName("answer 帧：只有 event/content（前端按可选字段读，多出字段即协议变更）")
        void answerFrameFields() throws Exception {
            RecordingSseEmitter emitter = emit(new AgentEvent.Answer("共 2 个空间"));

            JsonNode answer = jsonOf(emitter, 0);
            assertThat(answer.get("event").asText()).isEqualTo("answer");
            assertThat(answer.get("content").asText()).isEqualTo("共 2 个空间");
            assertThat(answer.has("kind")).isFalse();
            assertThat(answer.has("name")).isFalse();
        }

        @Test
        @DisplayName("metrics 帧：指标平铺进帧 + event 字段")
        void metricsFrameFields() throws Exception {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("searches", 3);
            values.put("sources", 5);

            RecordingSseEmitter emitter = emit(new AgentEvent.Metrics(values));

            JsonNode metrics = jsonOf(emitter, 0);
            assertThat(metrics.get("event").asText()).isEqualTo("metrics");
            assertThat(metrics.get("searches").asInt()).isEqualTo(3);
            assertThat(metrics.get("sources").asInt()).isEqualTo(5);
        }

        @Test
        @DisplayName("终止帧是原始文本 [DONE]（不是 JSON），并触发 complete()")
        void doneFrameIsRawText() {
            RecordingSseEmitter emitter = emit(new AgentEvent.Answer("完成"), new AgentEvent.Done());

            assertThat(emitter.payloads()).hasSize(2);
            assertThat(emitter.payloads().get(1)).isEqualTo("[DONE]");
            assertThat(emitter.frames().get(1).isDoneFrame()).isTrue();
            assertThat(emitter.awaitCompletion(1_000)).isTrue();
        }

        @Test
        @DisplayName("重复的 Done 被忽略：不会写出第二个 [DONE]")
        void duplicateDoneIsIgnored() {
            RecordingSseEmitter emitter = emit(new AgentEvent.Done(), new AgentEvent.Done());

            assertThat(emitter.payloads()).containsExactly("[DONE]");
        }

        @Test
        @DisplayName("写失败（客户端已断开）触发 onClientGone，不向调用方抛异常")
        void writeFailureCallsClientGone() {
            RecordingSseEmitter emitter = new RecordingSseEmitter();
            emitter.failOnSend();
            AtomicInteger clientGone = new AtomicInteger();
            SseAgentEventListener listener = new SseAgentEventListener(emitter, clientGone::incrementAndGet);

            listener.onEvent(new AgentEvent.Answer("回答"));

            assertThat(clientGone.get()).isEqualTo(1);
            assertThat(emitter.payloads()).isEmpty();
        }
    }

    // ==================== 循环级 ====================

    @Nested
    @DisplayName("循环级：事件序列与终止帧唯一性")
    class LoopLevel {

        @Test
        @DisplayName("正常跑完：回答 → Done")
        void cleanFinish() {
            ScriptedAgent agent = scripted(List.of(List.of(new AgentEvent.Answer("完成"))));
            agent.setFinishOnStep(true);

            RecordingAgentEventListener listener = run(agent, "问一句");

            assertThat(listener.events()).containsExactly(new AgentEvent.Answer("完成"), new AgentEvent.Done());
        }

        @Test
        @DisplayName("达最大步数：逐步回答 → 上限提示 → Done")
        void maxSteps() {
            ScriptedAgent agent = answeringAlways("部分回答");
            agent.setMaxSteps(2);

            RecordingAgentEventListener listener = run(agent, "问一句");

            List<AgentEvent> events = listener.events();
            assertSingleDoneLast(listener);
            assertThat(events).hasSize(4);
            assertThat(listener.joinedAnswers().lines().count()).isEqualTo(3);
            assertThat(listener.joinedAnswers()).contains("执行结束：达到最大步骤 (2)");
        }

        @Test
        @DisplayName("卡死终止：提示 → Done（且只有这一个终止帧）")
        void stuck() {
            ScriptedAgent agent = answeringAlways("重复回答");
            agent.setMaxSteps(5);
            for (int i = 0; i < 4; i++) {
                agent.getMessageList().add(new UserMessage("next-step"));
                agent.getMessageList().add(new AssistantMessage("repeated-text"));
            }

            RecordingAgentEventListener listener = run(agent, "问一句");

            assertSingleDoneLast(listener);
            assertThat(listener.lastAnswer()).isEqualTo("检测到循环，智能体已终止");
        }

        @Test
        @DisplayName("入参校验失败（状态非 IDLE）：纠错回答 → Done，且不进入循环")
        void invalidState() {
            ScriptedAgent agent = answeringAlways("不该被调用");
            agent.setState(AgentState.ERROR);

            RecordingAgentEventListener listener = run(agent, "问一句");

            assertThat(listener.events()).hasSize(2);
            assertThat(listener.joinedAnswers()).contains("无法从该状态运行代理");
            assertThat(listener.count(AgentEvent.Done.class)).isEqualTo(1);
            assertThat(agent.stepCalls()).isZero();
        }

        @Test
        @DisplayName("入参校验失败（空提示词）：纠错回答 → Done")
        void blankPrompt() {
            ScriptedAgent agent = answeringAlways("不该被调用");

            RecordingAgentEventListener listener = run(agent, "   ");

            assertThat(listener.events()).hasSize(2);
            assertThat(listener.joinedAnswers()).contains("用户提示不能为空");
            assertThat(listener.count(AgentEvent.Done.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("用户停止：当轮收尾，不发上限提示、不发 metrics，仍以 Done 结束")
        void stopped() {
            ScriptedAgent agent = answeringAlways("半截回答");
            agent.setMaxSteps(5);
            agent.setStopOnStep(true);
            agent.setSummary(Map.of("searches", 1));

            RecordingAgentEventListener listener = run(agent, "问一句");

            assertSingleDoneLast(listener);
            assertThat(listener.joinedAnswers()).doesNotContain("达到最大步骤");
            assertThat(listener.count(AgentEvent.Metrics.class)).isZero();
        }

        @Test
        @DisplayName("metrics 只在跑完的收尾发，且在 Done 之前")
        void metricsBeforeDone() {
            ScriptedAgent agent = scripted(List.of(List.of(new AgentEvent.Answer("完成"))));
            agent.setFinishOnStep(true);
            agent.setSummary(Map.of("searches", 3));

            RecordingAgentEventListener listener = run(agent, "问一句");

            assertThat(listener.events()).hasSize(3);
            assertThat(listener.events().get(0)).isInstanceOf(AgentEvent.Answer.class);
            assertThat(listener.events().get(1)).isEqualTo(new AgentEvent.Metrics(Map.of("searches", 3)));
            assertThat(listener.events().get(2)).isEqualTo(new AgentEvent.Done());
        }
    }

    // ==================== runStream 级 ====================

    @Nested
    @DisplayName("runStream 级：异步路径产出同样的帧")
    class RunStreamLevel {

        @Test
        @DisplayName("帧序列 = 事件序列，末帧 [DONE]")
        void framesMatchEvents() throws Exception {
            ScriptedAgent agent = scripted(List.of(
                    List.of(AgentEvent.Step.think("我先看看"),
                            AgentEvent.Step.tool("listSpaces", "共 2 个空间")),
                    List.of(new AgentEvent.Answer("你有两个空间"))));
            agent.setFinishOnStep(true);
            RecordingSseEmitter emitter = new RecordingSseEmitter();
            agent.useEmitter(emitter);

            agent.runStream("列一下空间");

            assertThat(emitter.awaitCompletion(5_000)).as("runStream 应在 5s 内收尾").isTrue();
            assertThat(emitter.payloads()).hasSize(4);
            assertThat(jsonOf(emitter, 0).get("kind").asText()).isEqualTo("think");
            assertThat(jsonOf(emitter, 1).get("name").asText()).isEqualTo("listSpaces");
            assertThat(jsonOf(emitter, 2).get("event").asText()).isEqualTo("answer");
            assertThat(jsonOf(emitter, 2).get("content").asText()).isEqualTo("你有两个空间");
            assertThat(emitter.payloads().get(3)).isEqualTo("[DONE]");
        }

        @Test
        @DisplayName("校验失败的流同样以 [DONE] 收尾（代理据此不再补一条'响应意外中断'）")
        void validationFailureStreamIsDoneTerminated() throws Exception {
            ScriptedAgent agent = answeringAlways("不该被调用");
            agent.setState(AgentState.ERROR);
            RecordingSseEmitter emitter = new RecordingSseEmitter();
            agent.useEmitter(emitter);

            agent.runStream("问一句");

            assertThat(emitter.awaitCompletion(5_000)).isTrue();
            assertThat(emitter.payloads()).hasSize(2);
            assertThat(jsonOf(emitter, 0).get("content").asText()).contains("无法从该状态运行代理");
            assertThat(emitter.payloads().get(1)).isEqualTo("[DONE]");
            assertThat(agent.stepCalls()).isZero();
        }

        @Test
        @DisplayName("客户端断开：当轮即停，不再进入下一步")
        void clientDisconnectStopsTheLoop() {
            ScriptedAgent agent = answeringAlways("回答");
            agent.setMaxSteps(5);
            RecordingSseEmitter emitter = new RecordingSseEmitter();
            emitter.failOnSend();
            agent.useEmitter(emitter);

            agent.runStream("问一句");

            assertThat(emitter.awaitCompletion(5_000)).isTrue();
            assertThat(agent.isStopped()).isTrue();
            assertThat(agent.stepCalls()).as("第一步写失败即置停止标记，不再进入第二步").isEqualTo(1);
        }
    }
}
