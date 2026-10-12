# Plan — 火山图库（huoshan-picture）

<!-- freshness: { owner: "lcl", reviewed: "2026-10-12" } -->

> 任务粒度：一个任务 = 一次可测试的改动 = 一个 commit。每个任务必须标注允许触碰的文件范围。
> **本文件是索引**：只留活跃任务 + 当前里程碑；实施记录、红绿输出、证据写 `docs/plans/records/`，这里只留指针。已收口里程碑整批结转 `docs/plans/archive/`（见文末「归档」）。
> **预算 ≤120 行**；超了就该结转了（保养清单见 vibe-handoff 的"文档保养"）。

## 活跃任务

- [x] T27.10（2026-09-29 独立 review P2-2；2026-10-01 用户批准）**助手确认卡片可核对性**：卡片摘要改由 backend 在 prepare 归一化后生成，随 `AiOperationPrepareVO.summary` 返回——空间名经 `ISpaceService` 读取（查不到回退展示 id；prepare 仍零写库、RBAC 留在执行时），目标列表列前 5 条图片 id / URL + 省略，分类/标签/命名规则行沿用现格式；引擎两个 prepare 工具改为**只透传**后端 summary（缺失/为空 fail-closed 报错，不再自算）——卡片与执行计划同源，消掉两份归一化规则的漂移面。SSE 帧已逐字透传 summary、前端卡片渲染行数组，两者零改动。依据见 `docs/decisions/2026-09-29-review-write-gate.md` ①-2。
  - 文件范围：backend `domain/vo/AiOperationPrepareVO.java`、`manager/ai/AiAssistantOperationManager.java` + `AiAssistantOperationManagerTest`；引擎 `tools/huoshan/PrepareUrlImportPicturesTool.java`、`PrepareBatchEditPicturesTool.java`、`HuoshanToolSupport.java` + `HuoshanPrepareToolsTest`。
  - 验收：backend 单测先红后绿（url_import ≤5 全列 / >5 省略；batch_edit id 截断与字段行、空间名缺失回退）；引擎透传与缺失报错断言；术语守卫 `check:assistant-copy` PASS；backend/引擎全量门禁绿；真机卡片显示"目标空间：默认空间（id：…）+ 目标清单"。
  - **2026-10-01 收口**：红绿（backend 先红 5 → 33 绿、引擎先红 3 → 8 绿）、全量门禁（backend 144 / 引擎 214）与真机（URL 导入卡片即后端摘要格式）全过，commit `c392ecc`；实施记录与证据见 `docs/plans/records/T27.10-确认卡片摘要后端生成.md`。

- [x] T43（2026-10-01 字据批准；设计、根因与验收全文见 `docs/designs/2026-10-01-门卫召回修复设计.md`）**助手范围门卫召回修复**：门卫从"裸吃全量历史 + 精确相等 + 拒绝不落库"改成"受控任务上下文 + 前缀匹配 + 拒绝一等公民"，一次覆盖三类误拒（搜图短主题追问 / 历史拒绝轮污染 / 携带 URL 的导入请求；第三形态 2026-10-01 线上复现，见 `docs/evidence/2026-10-01-prod-confirm-gate-readonly-probe.md` 补记），不动"范围外必须拒"的 fail-closed 底线。方案：A1 门卫输入 = System(SCOPE_PROMPT v2) + 上一轮任务上下文（至多 1 轮，跳过拒绝轮）+ 本轮消息；A2 拒绝路径写回 ASSISTANT 行（回放不再"问了没答"）；A3 判定改首个 IN_SCOPE/OUT_OF_SCOPE token 命中（无 token 仍 fail-closed）+ prompt v2 补三例；A4 范围措辞四处单源。
  - 文件范围：引擎 `agent/HuoshanAssistantAgent.java` + 新增 `agent/HuoshanAssistantAgentScopeTest.java`；不碰 BaseAgent/ToolCallAgent 循环本体、FlowWindowBasedChatMemory、ChatHistoryAssembler、backend、前端。
  - 验收：单测先红后绿（短跟进放行 / 污染隔离 / URL 放行 / 非协议输出 fail-closed / 拒绝写回；负向控制实测红）；`ai/agent` 门禁全绿；真机四项（T25 原三负向仍拒 + 短跟进放行 + 污染复测 + URL 复测）记 `docs/plans/records/T43-*.md`。
  - **2026-10-01 收口**：范围测试 12/12（先红 5）+ 引擎全量 214/214 + 真机四项全过（含 URL 导入直达卡片、零写入），commit `97deea3`；实施记录与证据见 `docs/plans/records/T43-门卫召回修复.md`。

- [x] T44（2026-10-12 字据批准；设计见 `docs/designs/2026-10-12-确认门收尾与结果回灌设计.md`）**确认结果回灌引擎**：写操作确认执行成功后，backend 调引擎新增内部端点把一条"操作结果"事实写入该会话记忆（USER 角色 + 哨兵前缀），下一轮模型续聊可读到执行结果；前端历史按前缀过滤、维持"回放无卡片"现状。
  - 文件范围：引擎 `agent/HuoshanAssistantSession.java`、`controller/HuoshanAssistantController.java`、`utils/ChatHistoryAssembler.java` + 对应测试；backend `manager/ai/AiAssistantProxyManager.java`、`manager/ai/AiAssistantOperationManager.java`、`controller/AiAssistantController.java` + 对应测试。不碰循环本体与前端。
  - 验收：引擎单测（端点落 USER 行 + 前缀；前端视图隐藏；回放可见）先红后绿；backend 单测（结果说明生成 / 确认成功后恰记录一次 / 记录失败不影响确认）先红后绿；两侧门禁全绿。
  - **2026-10-12 收口**：引擎先红（结果事实行未被过滤，5≠4）→ 19/19 绿；backend 74/74 绿；提交门禁全量 backend 153 / 引擎 212 全绿，commit `03f59d7`；实施记录与证据见 `docs/plans/records/T44-确认结果回灌引擎.md`。
  - **2026-10-12 真机复跑**：确认执行成功 → 引擎落 `【系统记录】` USER 事实行（`chat_message` 逐字核对）→ 续聊答"批量编辑已经完成…与您之前提交的计划一致" → 历史回放 `code=0`、6 轮、按码点检索不含事实行；证据见 `docs/plans/records/T44-T45-真机复跑.md`。

- [x] T45（2026-10-12 字据批准；同设计文档）**确认卡片后确定性收尾**：引擎 `ToolCallAgent.act()` 产出待确认操作后置 FINISHED，本轮 SSE 在卡片帧后即 [DONE]，不再多走一步模型；前端确认卡片按钮运行中禁用（纵深防护）。
  - 文件范围：引擎 `agent/ToolCallAgent.java` + `agent/ToolCallAgentTest.java`（全循环用例）；前端 `pages/AssistantPage.vue`。不碰 BaseAgent 循环、代理、backend。
  - 验收：新增 runLoop 全循环测试先红后绿（callLlm 恰一次、恰一张 Confirmation、恰一个 [DONE]、终态 FINISHED）；`act()` 用例补 FINISHED 断言；`check:assistant-copy` 与 type-check 不破基线。
  - **2026-10-12 收口**：引擎先红（主模型被调用 5 次，expected 1 but was 5）→ 14/14 绿；前端两项检查 PASS；提交门禁全量 backend 153 / 引擎 213 / type-check 124=基线，commit `b612cca`；实施记录与证据见 `docs/plans/records/T45-确认卡片后确定性收尾.md`。
  - **2026-10-12 真机复跑**：真机 SSE 帧序确认 `confirmation_required` 之后只剩同一 `act()` 的 prepare 工具帧、紧接 `[DONE]`，无尾随"请确认"文本、无第二次模型调用；证据见 `docs/plans/records/T44-T45-真机复跑.md`。

## 未决与非任务事项（不建任务）

> 只放**不属于任务**的东西：待办前置、待人工验收、按规则 13 判为"已知限制并关闭"的项。已关闭项不再挂账；要做的时候才开任务。已关闭/已收口的历史全文见 `docs/plans/archive/2026-09-未决与关闭事项.md`。

- **D2 引擎 `CorsConfig` 收紧（缓解未修）**：`allowCredentials(true)` + `allowedOriginPatterns("*")` 对所有站点反射 Origin 且允许带凭据；D1 回环绑定后远程浏览器够不到它，但**本机进程仍可利用**，不算已修好。
- **数据卫生：逻辑删除的历史残留未清（另开任务才做）**：`user` 377 行 / `space` 206 行 / `picture` 362 行（`isDelete=1`）——对应用不可见，但占库、且**唯一索引仍被占用**（同名词条/账号不可再注册）。清理要跨表物理删除，属独立任务。已清记录见 `docs/plans/records/probe-data-purge-2026-09-15.md` 与 `backend/sql/cleanup/2026-09-15-purge-probe-data.sql`。
  - 规则沉淀：① MySQL 的 `LIKE` 不支持 `[_]` 字符类，精确前缀一律用 `left(col,N)='...'`；② 不能照账号名猜是不是探针（`1234`/`admin` 是带 144/10 张图的在用账号），删前必须看内容足迹。
- **A/B 基线选取口径待用户拍板**：`tag_category` 自 T5 起查词表、已不是空跑接口，两轮 A/B 均出现"最慢基线高于被测路径"使 `net` 列算 0 或反向；`compare-ab.py` 未改。数字与明细见 `backend/jmeter/README.md` §5.6 与 `docs/plans/records/ab-first-run-2026-09-18.md`。

## 归档

- 存量修复批次 T1–T3.11（2026-09-12~13）→ `docs/plans/archive/2026-09-存量修复批次.md`
- AI 助手一期 T4–T26（2026-09-13~16）→ `docs/plans/archive/2026-09-AI助手一期.md`
- 安全加固与优化批次 T27–T42（2026-09-16~10-01，T27 写确认门 2026-10-01 部署上线）→ `docs/plans/archive/2026-09-安全加固与优化批次.md`
- 未决与关闭事项全文（2026-09-15~17）→ `docs/plans/archive/2026-09-未决与关闭事项.md`
