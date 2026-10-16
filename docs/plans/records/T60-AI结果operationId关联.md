# T60 — AI 结果 operationId 关联

> 2026-10-16。来自两日改动 review 修复批次（plan.md T60，P2，依赖 T59）；功能讲解同步在 `docs/features/F12-图库助手档3工具与联调.md`。
> 本文件只记实施过程、红绿输出、负向控制与证据。代码 commit `ab136dc`。

## 范围

- backend 修改：
  - `domain/vo/AiOperationResultVO.java`——新增 `operationId` 与 `summary`（既存计划摘要）。
  - `domain/dto/ai/AiOperationResultFact.java`（新增 record）——回灌体：`content` + `operationId` + `operationType` + `outcome`。
  - `manager/ai/AiAssistantOperationManager.java`——confirm 成功后附上 `operationId` + 既存计划摘要；
    `operationResultNote` 拼接身份与摘要；新增 `operationFailureNote` / `userSafeFailureReason`。
  - `manager/ai/AiAssistantProxyManager.java`——`recordOperationResult` 改收 `AiOperationResultFact`，body 携身份字段。
  - `controller/AiAssistantController.java`——确认**失败也回灌**（原异常继续上抛），成功回灌带身份与 `executed`。
- 引擎（`ai/agent`）修改：
  - `agent/HuoshanAssistantSession.java`——新增 `METADATA_OPERATION_ID_KEY` / `METADATA_OPERATION_TYPE_KEY` / `METADATA_OUTCOME_KEY`。
  - `controller/HuoshanAssistantController.java`——请求体增三个可选字段，非空才写进事实行的 metadata。
- frontend 修改：
  - `utils/assistantFormat.ts`——新增 `AssistantConfirmCardStatus` 与纯函数 `hasPendingConfirmation`。
  - `pages/AssistantPage.vue`——待确认期间禁用输入框/发送按钮并给说明行，`send()` 同步拦住 Enter 路径。
  - `scripts/check-assistant-format.mjs`、`scripts/check-assistant-copy.mjs`——常驻断言与文案契约同步。
- 不新增依赖 / 表 / 进程；**未改确认门安全设计**（Lua 原子消费、userId/chatId/digest 绑定、单次消费、best-effort 全部原样保留）。

## 病根（三条，来自 review）

1. **结果事实没有身份**：T44 回灌只写一句"用户已确认并执行批量编辑：提交 N 张"——同一会话里两张确认卡片
   （尤其两张都是批编）结果长得一模一样，模型分不清是哪一张完成了；`AiOperationResultVO` 上也没有 operationId。
2. **失败不回灌**：T44 明确"confirm 硬失败不记录"（当时判据是"卡面已就地报错，无结果可报"）。于是确认门拒绝
   （过期/已消费/别人的 operationId）后，模型下一轮仍以为那张卡片挂着，可能重讲"待确认"。
3. **前端放任堆卡片**：待确认卡片还挂在界面上时，用户仍可继续发消息生成第二张写计划——卡片一多，
   确认顺序与结果就对不上（第 1 条的放大镜）。

## 修复

- **结果带身份**：`confirm()` 消费并解析出存储计划后，用同源同构的 `buildSummary(plan, spaceName)` 现算**既存计划摘要**，
  连同 `operationId` 一起挂在结果 VO 上。回灌文本第一行是既成事实，随后附 `[操作 <operationId>]` 与摘要逐行：
  ```
  用户已确认并执行批量编辑：提交 2 张。[操作 9f3c…]
  目标空间：Smoke（id：30）
  目标图片：2 张（id：1、2）
  分类 → 风景
  ```
  身份与摘要**同时落进引擎 metadata**（`operationId` / `operationType` / `outcome`），与 T59 的来源标记同列持久化——
  文本给模型看，metadata 给机器识别。
- **失败也回灌**：`AiAssistantController.confirmOperation` 把 `confirm` 的 `RuntimeException` 接住 → 回灌一条
  `outcome=failed` 的事实（`用户确认的写操作未执行：<用户安全原因>。[操作 <operationId>]`）→ **原异常实例继续上抛**，
  失败语义一字不改。原因取 `userSafeFailureReason`：`BusinessException` 文案原样（本就是面向用户设计的），
  其余内部异常一律收敛为固定文案。
- **前端挡住新一轮**：只要**当前可见轮次**里还有卡片停在 `pending`/`confirming`，输入框与发送按钮禁用、`send()` 直接返回，
  并显示"还有待确认的操作：先在上方确认或取消，再继续对话"。判据只统计可见轮次（切会话/新对话后 `turns` 重置 ⇒
  不会把输入框永久锁死）。只挡"发新消息"——取消按钮、会话列表/历史查询、服务端授权都不受影响。
- **保留项**：确认门绑定（userId/chatId/digest）、单次消费（Lua 原子 DEL）、best-effort（引擎写失败只 warn，
  不影响 confirm 返回）**均未改动**。

## 红绿输出（可复跑）

```
cd backend && mvn -B test -Dtest=AiAssistantOperationManagerTest,AiAssistantOperationControllerTest,AiAssistantProxyManagerTest -DfailIfNoTests=false
cd ai/agent && mvn -B test -Dtest=HuoshanAssistantControllerOperationResultTest -DfailIfNoTests=false
cd frontend && npm run check:assistant-format
```

- **backend 先红**：`Tests run: 84, Failures: 13`，恰为本次新增/改写的断言——
  经理 9（`operationResultNote*` 三例 + `operationResultNoteUsesIdentityCarriedOnTheResult` +
  `confirmBatchEditCarriesOperationIdAndPlanSummary` / `confirmUrlImportCarriesOperationIdAndPlanSummary` +
  `operationFailureNote*` 两例 + `userSafeFailureReasonKeepsBusinessMessageButBuriesInternalDetail`）、
  控制器 2（`confirmRecordsOperationResultToEngineOnce`、`confirmRecordsFailureFactWithoutChangingError`）、
  代理 2（`recordsOperationResultToEngineWithInternalKeyAndJsonBody`、`recordsFailureFactWithOperationIdOnly`）。
- **backend 后绿**：同命令 `84/84`。
- **引擎先红**：`Tests run: 8, Failures: 2`（`carriesOperationIdentityInFactMetadata`、`omitsBlankIdentityFieldsFromFactMetadata`）。
  同文件 `toleratesContentOnlyRequest` 修复前即绿——它守的是"旧调用方只给 content 也能落库"，本就不该红。
- **引擎后绿**：`27/27`（含 `MessagesTest` / `ChatHistoryAssemblerTest` / `MessageConverterReplayTest` 回归）。
- **前端先红**：`npm run check:assistant-format` → `RESULT: 5 FAILED`（T60 那 5 条断言）；补上纯函数后 PASS。
- **提交门禁（pre-commit 全量，与 CI 同口径）**：backend **185/185**、引擎 **223/223**，全绿。
- **前端四项**：`check:assistant-format` / `check:assistant-copy`（含两条新文案）/ `npm run type-check`（0 错，基线未动）/
  `npx eslint .`（无输出）；另跑 `check:format-size` / `check:batch-crawl-result` / `check:outpainting-status` 全 PASS。

## 红是怎么造出来的（手法留档）

Java 侧"新契约 = 新签名/新字段"，直接写测试会先是**编译红**、拿不到行为红。做法：先把**签名与字段**补齐、
行为故意留旧（`operationResultNote` 保持旧格式、代理只送 `content`、控制器不记失败、两个新助手函数先返回兜底值），
跑一次拿到 13 条**断言红**；再实现真逻辑转绿。前端同款：先往 `check-assistant-format.mjs` 加断言（此时
`hasPendingConfirmation` 还不存在，可选调用让缺失表现为干净的 FAIL 而不是 TypeError 中断），拿到 5 条红再补函数。

## 已知限制 / 未覆盖

1. **失败回灌不知道操作类型**：确认门拒绝时计划已被消费（或本就不存在），拿不到类型 ⇒ 事实 metadata 无 `operationType`、
   文本也不提类型，只有 `operationId` 可以关联。执行阶段失败同理（异常上抛即离开可知类型的上下文）。
2. **失败原因的用户安全口径**：`BusinessException` 文案原样进模型上下文。本仓的 `BusinessException` 一直是面向用户
   设计的（确认门统一文案、picture service 业务拒绝文案），判为可接受；若将来有内部措辞混进 `BusinessException`，
   需要另立口径收口。
3. **摘要里的空间名是确认时回读的**：`withPlanContext` 用 `resolveSpaceName` 回读一次空间名（一次主键读），
   与 prepare 时的卡片摘要同源同构；期间空间被改名会有极小概率名字漂移，不影响 id 与数量。
4. **前端阻挡是体验约束、不是安全边界**：绕过前端直接调 `/operations/confirm` 仍由服务端确认门校验；
   阻挡期间用户也发不了纯查询消息（前端无从预判下一句是否生成写计划），点「取消」即可恢复——这是本次明确的设计取舍。
5. **每次确认失败都会回灌一条事实**：前端防双击 + `/operations/confirm` 限流（20/分钟）约束了量级，未做去重。
6. **未做真机端到端**：本轮证据 = 单测 + 常驻检查脚本；前端未做浏览器人工验收（输入框禁用、说明行、Enter 拦截均未真机走）。
