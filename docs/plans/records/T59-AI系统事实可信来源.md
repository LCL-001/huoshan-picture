# T59 — AI 系统事实可信来源

> 2026-10-16。来自两日改动 review 修复批次（plan.md T59，P2）；功能讲解同步在 `docs/features/F12-图库助手档3工具与联调.md`。
> 本文件只记实施过程、红绿输出、负向控制与证据。代码 commit `2fa3ae4`。

## 范围

- 引擎（`ai/agent`）修改：
  - `agent/HuoshanAssistantSession.java`——新增来源标记常量 `METADATA_SOURCE_KEY`（`huoshanSource`）、
    取值 `SOURCE_USER` / `SOURCE_SYSTEM_FACT`，以及两个纯判据 `isSystemFact(Map)` / `hasSourceMarker(Map)`；
    重写 `OPERATION_RESULT_PREFIX` 的注释（前缀只给模型看，不是可信判据）。
  - `agent/BaseAgent.java`——`rememberUserMessage` 写用户原文时打来源标记 `user`。
  - `controller/HuoshanAssistantController.java`——`recordOperationResult` 写事实时打来源标记 `system_fact`。
  - `utils/ChatHistoryAssembler.java`——前端历史隐藏改看持久化 metadata（旧行按前缀兜底）。
  - `utils/MessageConverter.java`——USER 行回放保留 metadata（来源标记活过持久化/回放）。
- 测试：`ChatHistoryAssemblerTest`（+2 例、1 例改名）、`MessageConverterReplayTest`（+2 例）、
  `HuoshanAssistantControllerOperationResultTest`（+1 例）、`AgentExplicitMemoryTest`（+1 断言）。
- 不新增依赖 / 表 / 进程；`chat_message.metadata` 列早就存在（`JacksonTypeHandler`），**无需改表**。
- 后端（`backend/`）零改动——事实文本仍由 `AiAssistantOperationManager.operationResultNote` 生成，前缀不变。

## 病根

T44 的结果事实以 **USER 角色 + 哨兵文本前缀 `【系统记录】`** 入库，前端历史靠 `content.startsWith(前缀)` 隐藏它。
于是这条**隐藏判据是用户可伪造的**：用户在输入框里敲一句以 `【系统记录】…` 开头的话，它会被当成系统事实
从前端历史里抹掉；反过来，任何照抄该前缀的文本也能冒充"系统说的"。文本前缀本身也不该被当成防注入边界。

## 修复

用**持久化的消息 metadata**（`chat_message.metadata` 列）承载可信来源，文本前缀降级为"只给模型看的来源提示"。

- 写侧落标记（服务端写库时定，用户无从伪造）：
  - `BaseAgent.rememberUserMessage` → `huoshanSource=user`；
  - `HuoshanAssistantController.recordOperationResult` → `huoshanSource=system_fact`（文本仍带 `【系统记录】` 前缀）。
- 读侧判据（`ChatHistoryAssembler.isHiddenUserRow`）三态：
  1. 标记为 `system_fact` → 隐藏（**唯一可信判据**，与文本无关）；
  2. 有标记但不是 `system_fact`（即 `user`）→ 不隐藏——用户照抄前缀也不会被吃掉；
  3. 无标记 → 本次改造之前的旧行，按文本前缀兜底（与旧行为一致）。
- 回放不丢标记：`MessageConverter.toMessage` 的 USER 分支由 `new UserMessage(text)` 改为
  `UserMessage.builder().text(text).metadata(metadata).build()`，让来源标记活过"落库 → 读回 → 回放"。
- 模型侧：事实仍以文本前缀 `【系统记录】` 呈现（模型读的是角色 + 文本，metadata 不上模型线），
  所以前缀就是给模型的来源提示——但它**不是**可信边界，见「已知限制」。

## 红绿输出（可复跑）

```
cd ai/agent
mvn -B test -Dtest=ChatHistoryAssemblerTest,MessageConverterReplayTest,HuoshanAssistantControllerOperationResultTest,AgentExplicitMemoryTest -DfailIfNoTests=false
```

- **红（修复前）**：`Tests run: 22, Failures: 5`，恰好是下列 5 例：
  - `ChatHistoryAssemblerTest.assemble_keepsUserMessageThatImitatesInternalPrefix`——用户照抄前缀的消息被错误隐藏；
  - `ChatHistoryAssemblerTest.assemble_hidesFactRowBySourceMarker`——不带前缀、只有 `system_fact` 标记的事实行未被隐藏；
  - `MessageConverterReplayTest.retainsSourceMarkerWhenReplayingUserRows`——回放把 USER 行的 metadata 丢了；
  - `HuoshanAssistantControllerOperationResultTest.marksRecordedFactAsSystemFact`——事实未打来源标记；
  - `AgentExplicitMemoryTest.loadsOnceAndWritesExplicitly`——用户原文未打来源标记。
  （`MessageConverterReplayTest.persistsSourceMarkerIntoChatMessageMetadata` 修复前就是绿的：`toChatMessage` 本就整体复制 metadata，
  留作回归守护。）
- **绿（修复后）**：同命令 `Tests run: 22, Failures: 0`。
- **负向控制（规则 12）**：只把 `ChatHistoryAssembler.isHiddenUserRow` 退回"只看文本前缀"（保留其余修复）→
  **恰好那 2 例 assembler 测试红**（`assemble_keepsUserMessageThatImitatesInternalPrefix`、`assemble_hidesFactRowBySourceMarker`），
  旧行兜底那例仍绿；还原即绿。证明这两条测试钉的是 metadata 机制，不是"改了点什么"。
- **全量门禁（ai/agent，pre-commit 口径）**：`mvn -B test -Dtest='!MyAiAgentApplicationTests,!ProductScopeTest,!PgVectorVectorStoreConfigTest,!FileOperationToolTest,!PDFGenerationToolTest,!ResourceDownloadToolTest,!WebScrapingToolTest,!WebSearchToolTest' -DfailIfNoTests=false`
  → **220/220 绿**（本轮新增 5 例 + 1 断言）。commit 时 pre-commit 实跑：backend **175/175**、ai/agent **220/220**、docs 一致性 PASS。

## 已知限制 / 未覆盖

1. **对模型而言仍不是防注入边界**（本任务明确"不宣称绝对防注入"）：模型只看得到角色 + 文本，metadata 不上模型线，
   所以注入防护（"用户文本冒充系统事实"）在本轮**没有加强**——加强的只是**前端历史隐藏**与**来源识别**。
   若日后要防模型被普通用户文本冒充，需换更强机制（如另起角色/通道），届时另立字据。
2. **判断"普通前缀消息"依赖新行带来源标记**：只有经 `/chat`（`BaseAgent.rememberUserMessage`）与
   T44 事实端点写下的行才有标记。绕过这两条路直接写 `chat_message` 的行（无此场景）会被当旧行处理。
3. **旧行两义性无解**：改造前落库的行没有来源标记，`【系统记录】` 开头的用户旧行与旧事实行无法区分，
   只能一起按前缀隐藏（兜底口径）。这是历史数据的固有代价，未做数据回填。
4. **单测覆盖、未真机联调**：本轮以单测（含红/绿/负向控制）为证据，未跑 backend 8123 + 引擎 8124 的端到端；
   `MessageConverter.toMessage` 保留 metadata 对 OpenAI 请求体的影响经 jar 层核实（OpenAI 侧只序列化角色 + 文本，
   metadata 仅用于本地），并由全量门禁回归覆盖。
