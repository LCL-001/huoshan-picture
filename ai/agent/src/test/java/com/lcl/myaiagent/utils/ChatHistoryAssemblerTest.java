package com.lcl.myaiagent.utils;

import com.lcl.myaiagent.agent.MyManus;
import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.model.vo.ChatMessageVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatHistoryAssembler 的组装规则测试：
 * 轮次划分、内部提示过滤、工具步骤还原、askHuman 提问还原、超长截断。
 */
class ChatHistoryAssemblerTest {

    /** 构造一行消息。metadata 模拟 JacksonTypeHandler 反序列化后的结构 */
    private ChatMessage row(long id, MessageType type, String content, Map<String, Object> metadata) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setConversationId("c1");
        m.setMessageType(type);
        m.setContent(content);
        m.setMetadata(metadata);
        m.setCreateTime(new Date());
        return m;
    }

    @Test
    @DisplayName("普通问答：user 轮 + assistant 轮，无步骤")
    void assemble_plainChat() {
        List<ChatMessage> rows = List.of(
                row(1, MessageType.USER, "你好", null),
                row(2, MessageType.ASSISTANT, "你好！有什么可以帮你？", null));

        List<ChatMessageVO> turns = ChatHistoryAssembler.assemble(rows);

        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).getRole());
        assertEquals("你好", turns.get(0).getContent());
        assertEquals("assistant", turns.get(1).getRole());
        assertEquals("你好！有什么可以帮你？", turns.get(1).getContent());
        assertNull(turns.get(1).getSteps());
    }

    @Test
    @DisplayName("工具调用轮：内部提示并入 assistant 轮，TOOL 行还原为步骤")
    void assemble_toolCallTurn() {
        List<ChatMessage> rows = new ArrayList<>();
        rows.add(row(1, MessageType.USER, "帮我搜一下云南旅游攻略", null));
        // agent 内部下一步提示：以 USER 角色入库，但不应单独成轮
        rows.add(row(2, MessageType.USER, MyManus.NEXT_STEP_PROMPT_TEXT, null));
        // 模型发起工具调用的 assistant 行：content 为空
        rows.add(row(3, MessageType.ASSISTANT, "", Map.of("messageType", "ASSISTANT")));
        // 工具响应行：内容在 metadata.toolResponses 里
        rows.add(row(4, MessageType.TOOL, "", Map.of(
                "messageType", "TOOL",
                "toolResponses", List.of(Map.of(
                        "id", "t1", "name", "searchWeb",
                        "responseData", "\"搜索到3条结果\"")))));
        // 最终回答
        rows.add(row(5, MessageType.ASSISTANT, "为你整理了云南旅游攻略如下……", null));

        List<ChatMessageVO> turns = ChatHistoryAssembler.assemble(rows);

        // 内部提示不能单独成轮：共 1 个 user 轮 + 1 个 assistant 轮
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).getRole());
        assertEquals("assistant", turns.get(1).getRole());
        // 步骤还原：名字来自工具响应，内容已去掉 JSON 引号
        assertNotNull(turns.get(1).getSteps());
        assertEquals(1, turns.get(1).getSteps().size());
        assertEquals("searchWeb", turns.get(1).getSteps().get(0).getName());
        assertEquals("搜索到3条结果", turns.get(1).getSteps().get(0).getContent());
        // 回答是最后的 assistant 文本
        assertEquals("为你整理了云南旅游攻略如下……", turns.get(1).getContent());
    }

    @Test
    @DisplayName("askHuman：toolCalls 参数里的提问还原为回答")
    void assemble_askHumanFromToolCalls() {
        List<ChatMessage> rows = List.of(
                row(1, MessageType.USER, "帮我规划行程", null),
                row(2, MessageType.ASSISTANT, "", Map.of(
                        "messageType", "ASSISTANT",
                        "toolCalls", List.of(Map.of(
                                "id", "t1", "type", "function", "name", "askHuman",
                                "arguments", "{\"inquire\": \"预算大概多少？\"}")))),
                row(3, MessageType.TOOL, "", Map.of(
                        "messageType", "TOOL",
                        "toolResponses", List.of(Map.of(
                                "id", "t1", "name", "askHuman",
                                "responseData", "\"[ASK_HUMAN]预算大概多少？\"")))));

        List<ChatMessageVO> turns = ChatHistoryAssembler.assemble(rows);

        assertEquals(2, turns.size());
        assertEquals("assistant", turns.get(1).getRole());
        // 提问作为回答内容渲染，且不带 [ASK_HUMAN] 前缀
        assertEquals("预算大概多少？", turns.get(1).getContent());
    }

    @Test
    @DisplayName("超长工具输出截断到 2000 字符")
    void assemble_truncatesLongStepContent() {
        char[] big = new char[3000];
        java.util.Arrays.fill(big, 'x');
        List<ChatMessage> rows = List.of(
                row(1, MessageType.USER, "搜一下", null),
                row(2, MessageType.TOOL, "", Map.of(
                        "messageType", "TOOL",
                        "toolResponses", List.of(Map.of(
                                "id", "t1", "name", "searchWeb",
                                "responseData", new String(big))))),
                row(3, MessageType.ASSISTANT, "完成", null));

        List<ChatMessageVO> turns = ChatHistoryAssembler.assemble(rows);

        String stepContent = turns.get(1).getSteps().get(0).getContent();
        assertEquals(2001, stepContent.length()); // 2000 + 省略号
        assertTrue(stepContent.endsWith("…"));
    }

    @Test
    @DisplayName("真实 advisor 数据形态：只有 toolCall 行没有 TOOL 行，也能还原步骤")
    void assemble_blankToolCallRowCreatesStep() {
        // MessageChatMemoryAdvisor 实际写库形态：内部提示 + 空文本 toolCall 行，无 TOOL 响应行
        List<ChatMessage> rows = List.of(
                row(1, MessageType.USER, "帮我查上海近期的活动", null),
                row(2, MessageType.USER, MyManus.NEXT_STEP_PROMPT_TEXT, null),
                row(3, MessageType.ASSISTANT, "", Map.of(
                        "messageType", "ASSISTANT",
                        "toolCalls", List.of(Map.of(
                                "id", "t1", "type", "function", "name", "searchWeb",
                                "arguments", "{\"query\": \"上海 秋季 3天 活动\"}")))),
                row(4, MessageType.USER, "上海，秋季，3天", null),
                row(5, MessageType.ASSISTANT, "根据搜索结果……", null));

        List<ChatMessageVO> turns = ChatHistoryAssembler.assemble(rows);

        // 4 轮：user / assistant(步骤、无回答) / user / assistant(回答)
        assertEquals(4, turns.size());
        assertEquals("user", turns.get(0).getRole());
        assertEquals("assistant", turns.get(1).getRole());
        assertEquals("user", turns.get(2).getRole());
        assertEquals("assistant", turns.get(3).getRole());
        assertNotNull(turns.get(1).getSteps());
        assertEquals(1, turns.get(1).getSteps().size());
        assertEquals("searchWeb", turns.get(1).getSteps().get(0).getName());
        // 单字段参数直接取值展示
        assertEquals("上海 秋季 3天 活动", turns.get(1).getSteps().get(0).getContent());
        // 步骤轮没有回答文本，最终回答在最后一轮
        assertEquals("", turns.get(1).getContent());
        assertEquals("根据搜索结果……", turns.get(3).getContent());
    }

    @Test
    @DisplayName("toolCall 行与 TOOL 行并存时去重：步骤内容以真实响应为准")
    void assemble_deduplicatesToolStepWithResponse() {
        List<ChatMessage> rows = List.of(
                row(1, MessageType.USER, "搜一下", null),
                row(2, MessageType.ASSISTANT, "", Map.of(
                        "messageType", "ASSISTANT",
                        "toolCalls", List.of(Map.of(
                                "id", "t1", "type", "function", "name", "searchWeb",
                                "arguments", "{\"query\": \"云南旅游\"}")))),
                row(3, MessageType.TOOL, "", Map.of(
                        "messageType", "TOOL",
                        "toolResponses", List.of(Map.of(
                                "id", "t1", "name", "searchWeb",
                                "responseData", "\"搜索到5条结果\"")))),
                row(4, MessageType.ASSISTANT, "完成", null));

        List<ChatMessageVO> turns = ChatHistoryAssembler.assemble(rows);

        assertEquals(1, turns.get(1).getSteps().size());
        assertEquals("搜索到5条结果", turns.get(1).getSteps().get(0).getContent());
    }

    @Test
    @DisplayName("SYSTEM 行不渲染")
    void assemble_skipsSystemRows() {
        List<ChatMessage> rows = List.of(
                row(1, MessageType.SYSTEM, "你是助手", null),
                row(2, MessageType.USER, "你好", null),
                row(3, MessageType.ASSISTANT, "你好呀", null));

        List<ChatMessageVO> turns = ChatHistoryAssembler.assemble(rows);

        assertEquals(2, turns.size());
    }
}
