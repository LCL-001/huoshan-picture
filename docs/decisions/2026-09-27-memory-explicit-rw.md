# 记忆读写去 advisor 化 + 压缩预算扩容（T32）

> 自 docs/decisions.md 外移（2026-09-27）；**口径以 decisions.md 表内那一行为准**，本文件保留完整原文。

## 决定

引擎的会话记忆**不再走 Spring AI 的 `MessageChatMemoryAdvisor`**，改由 agent 循环显式读写（T32，用户提出"不使用 advisor 解决"）：

- **读**：`BaseAgent.runLoop` 运行开始调用一次 `chatMemory.get(conversationId)`，过滤 system 行后作为本轮固定前缀（`historyContext`）；`ToolCallAgent.think()` 发提示词 = 前缀 + 本轮 messageList（+ 下一步提示）。
- **写**：运行开始写一次用户消息；每步模型调用后写一次助手消息（`rememberAssistantMessage`）。工具响应行仍不落库（T12 以来口径不变）。
- **指纹去重退役**：advisor 时代每轮回写最后一条用户消息、靠 `MessageConverter.fingerprint` 兜底；advisor 移除后写入方只有两处、每条只写一次，去重连同它的两个副作用（相同问题第二遍不进记忆、每条 add 全量扫描）一并移除。
- **压缩预算 4096 → 32768**：预算不是模型上限（主脑 1M 上下文），是单次请求的成本护栏；4096 会让几乎所有真实会话都触发摘要，32k 下典型 20 轮会话零压缩（单测实测 0 次调用、命中率 100%），超长会话仍有水位线增量摘要兜底。

## 为什么

advisor 的语义（每步重读全量记忆注入 + 回写请求里最后一条用户消息）是为"每次请求只带新消息"的无状态聊天设计的；本引擎的 ReAct 循环每轮重提整段 messageList，两者叠加导致：同一段上下文每轮在提示词里出现两份并逐轮膨胀（token 白花近一倍）；同一条用户消息每轮回写一次（入库侧靠指纹去重兜住——"实测重复 3 份"是短会话取样，实为逐轮重复）。显式读写后每条消息恰好一次、前缀恰好一份，行为可测试（`AgentExplicitMemoryTest`）。

## 影响

- agent 内部提示（下一步提示/卡死提示）不再入库：旧入库是 advisor 回写的意外副作用，前端渲染本来就把这类行过滤掉，故用户不可见差异；历史旧行仍被 `ChatHistoryAssembler.isInternalPrompt` 过滤。
- 用户隔轮重复同一句提问，第二遍会进记忆并出现在回放里（旧去重会丢）。
- 线上引擎仍是 advisor 版本，差异在下次部署引擎 jar 时生效。

## 守护与证据

`AgentExplicitMemoryTest`（接线）+ `FlowWindowBasedChatMemoryAddTest`（写入契约）+ `FlowWindowBasedChatMemoryMetricsTest` 双场景（4096 对照 65%/14→7；32768 零调用）。两项负向控制实测红。实施与证据全文见 `docs/plans/records/T32-记忆显式读写.md`。
