package com.lcl.myaiagent.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.myaiagent.agent.BaseAgent;
import com.lcl.myaiagent.agent.MyManus;
import com.lcl.myaiagent.agent.ToolCallAgent;
import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.model.vo.ChatMessageVO;
import com.lcl.myaiagent.tools.AskHumanTool;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 把 chat_message 的扁平行组装成"轮"结构的前端历史视图。
 * <p>
 * 组装规则：
 * 1. 真实用户消息开启一轮；agent 内部提示（下一步提示/卡死提示）虽以 USER 角色入库，
 *    但归入当前 assistant 轮，不单独成轮；
 * 2. TOOL 行与空文本的 toolCall 行还原为该轮的 steps（折叠条内容），
 *    内容过长截断，[ASK_HUMAN] 响应还原为面向用户的提问；
 * 3. 最后一条面向用户的 assistant 文本作为该轮的 answer，并关闭本轮。
 * </p>
 */
public final class ChatHistoryAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单个步骤内容上限：工具原始输出可能很长，超出部分截断 */
    private static final int MAX_STEP_CONTENT = 2000;

    private ChatHistoryAssembler() {
    }

    public static List<ChatMessageVO> assemble(List<ChatMessage> rows) {
        List<ChatMessageVO> result = new ArrayList<>();
        List<ChatMessageVO.StepVO> steps = new ArrayList<>();
        String answer = null;
        String assistantId = null;
        Date lastTime = null;

        for (ChatMessage m : rows) {
            String type = m.getMessageType().name();
            String content = m.getContent();

            if ("SYSTEM".equals(type)) {
                continue;
            }
            if ("USER".equals(type)) {
                if (isInternalPrompt(content)) {
                    continue;
                }
                flushTurn(result, assistantId, answer, steps, lastTime);
                steps = new ArrayList<>();
                answer = null;
                assistantId = null;
                lastTime = null;
                result.add(ChatMessageVO.builder()
                        .id(String.valueOf(m.getId()))
                        .role("user")
                        .content(content)
                        .createTime(m.getCreateTime())
                        .build());
                continue;
            }

            // 以下都是 assistant 轮的组成部分
            if (assistantId == null) {
                assistantId = String.valueOf(m.getId());
            }
            lastTime = m.getCreateTime();

            if ("TOOL".equals(type)) {
                String response = firstToolResponse(m);
                if (response == null) {
                    continue;
                }
                response = ToolCallAgent.normalizeToolResponseData(response);
                if (response.startsWith(AskHumanTool.ASK_HUMAN_PREFIX)) {
                    // askHuman 的提问面向用户，作为回答渲染而不是步骤
                    answer = response.substring(AskHumanTool.ASK_HUMAN_PREFIX.length()).trim();
                    continue;
                }
                upsertToolStep(steps, firstToolResponseName(m), truncate(response));
                continue;
            }

            // ASSISTANT
            if (content == null || content.isBlank()) {
                String question = extractAskHumanQuestion(m);
                if (question != null) {
                    answer = question;
                    continue;
                }
                // Memory Advisor 只持久化模型的 toolCall 决策行，不写工具响应行——
                // 每个非 askHuman 的 toolCall 生成一个步骤（后续 TOOL 行会用真实响应覆盖内容）
                for (Object item : toolCalls(m)) {
                    if (item instanceof Map<?, ?> toolCall && toolCall.get("name") != null) {
                        steps.add(ChatMessageVO.StepVO.builder()
                                .name(String.valueOf(toolCall.get("name")))
                                .content(truncate(formatArguments(String.valueOf(toolCall.get("arguments")))))
                                .build());
                    }
                }
                continue;
            }
            answer = content;
        }
        flushTurn(result, assistantId, answer, steps, lastTime);
        return result;
    }

    /** 收尾一轮 assistant：没有 steps 时置 null，避免前端渲染空折叠条 */
    private static void flushTurn(List<ChatMessageVO> result, String assistantId, String answer,
                                  List<ChatMessageVO.StepVO> steps, Date lastTime) {
        if (assistantId == null) {
            return;
        }
        result.add(ChatMessageVO.builder()
                .id(assistantId)
                .role("assistant")
                .content(answer == null ? "" : answer)
                .steps(steps.isEmpty() ? null : steps)
                .createTime(lastTime)
                .build());
    }

    private static boolean isInternalPrompt(String content) {
        return content != null && (content.startsWith(MyManus.NEXT_STEP_PROMPT_TEXT)
                || content.startsWith(BaseAgent.STUCK_PROMPT_PREFIX));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolResponses(ChatMessage m) {
        Map<String, Object> metadata = m.getMetadata();
        if (metadata == null || !(metadata.get("toolResponses") instanceof List<?> list)) {
            return List.of();
        }
        return (List<Map<String, Object>>) list;
    }

    private static String firstToolResponse(ChatMessage m) {
        return toolResponses(m).stream()
                .map(r -> (String) r.get("responseData"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String firstToolResponseName(ChatMessage m) {
        return toolResponses(m).stream()
                .map(r -> (String) r.get("name"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("工具调用");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolCalls(ChatMessage m) {
        Map<String, Object> metadata = m.getMetadata();
        if (metadata == null || !(metadata.get("toolCalls") instanceof List<?> list)) {
            return List.of();
        }
        return (List<Map<String, Object>>) list;
    }

    /**
     * 同名步骤去重式写入：TOOL 响应行与它前面的 toolCall 决策行描述的是同一次调用，
     * 若最后一步同名则用真实响应覆盖参数摘要，否则追加新步骤。
     */
    private static void upsertToolStep(List<ChatMessageVO.StepVO> steps, String name, String content) {
        if (!steps.isEmpty() && name.equals(steps.get(steps.size() - 1).getName())) {
            steps.get(steps.size() - 1).setContent(content);
            return;
        }
        steps.add(ChatMessageVO.StepVO.builder().name(name).content(content).build());
    }

    /** 把工具参数 JSON 转成可读文本（单字段直接取值，多字段 k: v 拼接，解析失败按原文展示） */
    private static String formatArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            var node = MAPPER.readTree(arguments);
            if (node.isObject() && node.size() == 1) {
                return node.fields().next().getValue().asText();
            }
            if (node.isObject()) {
                StringBuilder sb = new StringBuilder();
                node.fields().forEachRemaining(e -> sb.append(e.getKey()).append(": ").append(e.getValue().asText()).append("\n"));
                return sb.toString().trim();
            }
            return arguments;
        } catch (Exception e) {
            return arguments;
        }
    }

    /** 从空文本 assistant 行的 toolCalls 元数据里提取 askHuman 的提问 */
    private static String extractAskHumanQuestion(ChatMessage m) {
        Map<String, Object> metadata = m.getMetadata();
        if (metadata == null || !(metadata.get("toolCalls") instanceof List<?> toolCalls)) {
            return null;
        }
        for (Object item : toolCalls) {
            if (item instanceof Map<?, ?> toolCall && "askHuman".equals(toolCall.get("name"))) {
                try {
                    String question = MAPPER.readTree(String.valueOf(toolCall.get("arguments")))
                            .path("inquire").asText(null);
                    if (question != null && !question.isBlank()) {
                        return question.trim();
                    }
                } catch (Exception ignored) {
                    // arguments 非法 JSON 时按无提问处理
                }
            }
        }
        return null;
    }

    private static String truncate(String content) {
        return content.length() > MAX_STEP_CONTENT ? content.substring(0, MAX_STEP_CONTENT) + "…" : content;
    }
}
