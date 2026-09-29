# 2026-09-29 独立 review — T27.5–T27.8 助手写操作确认门（规则 13）

> 状态：**有条件通过**（0 条 P1；2 条 P2 待用户拍板；其余判已知限制并关闭）。口径表行见 `docs/decisions.md` 2026-09-29；复核范围与未验证项另见 `docs/handoff/0033-2026-09-29-写确认门独立review.md`。本文件写定后不可变（勘误除外）。

## 形态与范围

- **独立上下文**的只读判断会话（未参与 T27.5–T27.8 实施，明确不采信记录自述）。为核实结论**跑了三类现成门禁**（真 Redis 集成测试、前端常驻断言、文档一致性），**未改任何代码**；结论落定后由本会话写入 `docs/**` 档案。
- 复核对象：`2012592`（T27.5 确认凭据）、`086822c`（T27.6 写工具改准备）、`e5208c9`（T27.7 确认卡片）、`472d40a`（T27.8 收口文档）。
- 判据（T27.8 指定）：确认门安全设计、载荷形状识别的取舍、协议三处同步的完整性、与 `docs/spec.md` 写安全边界的一致性——即"做得对不对"，不复述实现过程。

## 结论：有条件通过

### ① 现在修（2 条 P2，待用户批准建任务）

| # | 判定 | 处置 |
|---|---|---|
| ①-1 | **确认请求吃全站 axios 10s 超时，而 URL 入库是服务端同步逐条执行**（P2，用户可见、可达且几乎必现于多图导入）——`frontend/src/request.ts:10` 的 `timeout: 10000` 是 `confirmAssistantOperation`（`api/assistantController.ts:40`，未覆写超时）唯一的时限；服务端 `AiAssistantOperationManager.executeUrlImport` 在请求线程里逐条走既有上传（单条 `URLFilePictureUpload:206-207` connect 5s + read 10s，再叠 COS 转存，上限 20 条）。⇒ 多图导入必然在客户端超时：卡片显示 **`确认未完成：timeout of 10000ms exceeded`**（axios 英文原文，`CONFIRM_FAILURE_FALLBACK` 因 `e.message` 非空而不可达），而服务端**照常执行完毕并写入**——卡片与事实相反。这也是一处回归：改造前执行在引擎侧，用的是 `application.yaml:103` 的 30s read-timeout 且无浏览器时限 | **待批准**：建议列为 T27.9（修缺陷，无需字据）——确认请求单独放开超时（建议与对话流一致：不限时），并把网络中断/超时映射成专用文案（"可能已在后台执行，请到空间里查看结果，不要重复提交"）；验收=前端断言 + 一次 5~10 条 URL 的真机导入；若要做彻底，正解是把 confirm 改为异步（202+轮询），本任务不做 |
| ①-2 | **确认卡片无法被人工核对**（P2，需字据）——卡片摘要只有"目标空间 **id**：N / 图片：N 张 / 分类→X / 标签→Y"（`PrepareBatchEditPicturesTool:77-88`）与"目标空间 id：N / 入库 URL：N 条"（`PrepareUrlImportPicturesTool:84-87`）：空间给的是不可读的雪花 id（不是空间名），目标实体只给条数（既无图片 id 也无 URL）。确认门的前提是"用户显式确认的内容 = 授权内容"，而当前用户**核对不了自己在授权哪个空间、哪些目标**——只能核对字段变化。另：摘要行由**引擎**在提交前自算（后端不参与、也不校验），"卡片 = 计划"靠两处归一化规则不漂移维持（本次逐条比对：引擎先归一、后端归一在其输出上幂等，今天不存在可达的分歧，但这是约定不是结构） | **待批准 + 字据**：建议列为 T27.10——摘要改由 **backend 从既存计划生成**（空间名经 spaceService 取、前 N 条目标 id/URL + 省略号），随 `AiOperationPrepareVO` 返回，引擎只透传 ⇒ 卡片与执行计划同源、可核对，并顺带消掉"两份归一化规则"与"未认证文本进卡片"两个面 |

### ② 判为已知限制并关闭（P3，写入 `docs/features/F12-图库助手档3工具与联调.md` 已知限制 13）

1. **prepare 无频控/无并发上限**：每次 prepare 写一个 Redis 键（默认 TTL 10 分钟、约 1KB），调用方必须持用户本人凭据 ⇒ 上限是"自己折腾自己"；与已关闭的 ticket 频控、chatId 长度同族，按 T27 边界"不重复处理"关闭。
2. **卡片摘要不进后端校验、也不落库**：摘要只是展示层，执行内容以后端既存计划为准；异常载荷（如被污染的 MCP 返回）最多让用户看到一张点下去必然失败的卡片（confirm 端 fail-closed）。若做 ①-2 会被顺带收敛。
3. **batch_edit 的 `requestedCount` 是"提交条数"而非"影响条数"**（图库按空间+id 过滤、非本空间 id 静默跳过）——核对 F12 已知限制 6/11 的分类正确，本次不新增。

## 已验证清单（本次实跑/实读）

1. **真 Redis 集成 9/9 绿**（本会话独立复跑 `AiAssistantOperationManagerRedisIntegrationTest`，Lettuce + 本机 Redis 5.x）：恰好一次消费并删键、重放零写入、8 线程并发恰好一次、他人 userId / 换 chatId / 篡改 digest 三类拒绝且保留原值、TTL 过期、存储计划被篡改 fail-closed。
2. **前端常驻断言 PASS**（复跑 `npm run check:assistant-format`），**文档一致性门禁 PASS**（`.githooks/check-docs-consistency.mjs`）。
3. **协议三处同步完整**：引擎 `SseAgentEventListener` 帧映射 + `SseEventProtocolContractTest` 帧级字段断言；代理 `AiAssistantProxyManagerTest.relaysConfirmationRequiredFramesVerbatim`（陌生事件逐字透传）；前端 `parseAssistantConfirmation` 四拒一收（`check-assistant-format` 有断言），F13 协议表已补行。
4. **"模型不可能直接写库"是结构性的**：直接写工具类、DTO 与 `HuoshanApiClient` 两个写方法已删；装配测试断言旧工具名不在清单（Spring AI 只按注册回调解析）；prepare 全流程只打 `/ai/assistant/operations/prepare` 的请求级断言在。
5. **确认不绕过授权**：`confirm` 只收 operationId+chatId+digest（无业务 payload），执行用 Redis 里的既存计划；`loginUser` 取自 confirm 请求 ⇒ 批编走 `PictureServiceImpl.editPictureByBatch:1352-1360` 的空间归属校验、URL 入库走 `URLFilePictureUpload` 的逐跳 DNS 重解析 + 内网黑名单 + 魔数 + 2M 上限 + 后缀白名单；`prepare` 明确不做 RBAC（单一事实源在执行时）。
6. **Lua 语义**：存储脚本 DEL+HSET+EXPIRE 原子（不会留下无 TTL 的计划）；消费脚本在"用户/会话/digest 全匹配 + `redis.sha1hex(plan)==digest` + 单次删除"全过后才返回计划，任一步不满足保留原值。
7. **spec 写安全边界逐句对照通过**：模型只提交计划（零写库）、backend 持有并原子消费、执行=既存计划、模型自然语言不构成授权、确认不替代登录态/RBAC/额度——代码路径与文字一致。

## 我没能验证什么（review 自述）

真机浏览器联调（T27.8 的四条正/负向路径未复跑，只读了记录）；**未复跑 backend 134 / 引擎 207 全量门禁**（只独立重跑了上述三类）；未在真实模型对话里验证"模型是否老实 prepare 而不宣称已写"；**①-1 的判断基于超时配置与代码路径的最坏值，不是实测耗时分布**（建议修完用一次多图导入实测确认）；线上（prod）一切未碰——本机三服务未起。
