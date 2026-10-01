# Plan — 火山图库（huoshan-picture）

<!-- freshness: { owner: "lcl", reviewed: "2026-10-01" } -->

> 任务粒度：一个任务 = 一次可测试的改动 = 一个 commit。每个任务必须标注允许触碰的文件范围。
> **本文件是索引**：只留活跃任务 + 当前里程碑；实施记录、红绿输出、证据写 `docs/plans/records/`，这里只留指针。已收口里程碑整批结转 `docs/plans/archive/`（见文末「归档」）。
> **预算 ≤120 行**；超了就该结转了（保养清单见 vibe-handoff 的"文档保养"）。

## 活跃任务

- [ ] T27.10（2026-09-29 独立 review P2-2；**2026-10-01 用户批准**）**助手确认卡片可核对性**：卡片摘要改由 backend 在 prepare 归一化后生成，随 `AiOperationPrepareVO.summary` 返回——空间名经 `ISpaceService` 读取（查不到回退展示 id；prepare 仍零写库、RBAC 留在执行时），目标列表列前 5 条图片 id / URL + 省略，分类/标签/命名规则行沿用现格式；引擎两个 prepare 工具改为**只透传**后端 summary（缺失/为空 fail-closed 报错，不再自算）——卡片与执行计划同源，消掉两份归一化规则的漂移面。SSE 帧已逐字透传 summary、前端卡片渲染行数组，两者零改动。依据见 `docs/decisions/2026-09-29-review-write-gate.md` ①-2。
  - 文件范围：backend `domain/vo/AiOperationPrepareVO.java`、`manager/ai/AiAssistantOperationManager.java` + `AiAssistantOperationManagerTest`；引擎 `tools/huoshan/PrepareUrlImportPicturesTool.java`、`PrepareBatchEditPicturesTool.java`、`HuoshanToolSupport.java` + `HuoshanPrepareToolsTest`。
  - 验收：backend 单测先红后绿（url_import ≤5 全列 / >5 省略；batch_edit id 截断与字段行、空间名缺失回退）；引擎透传与缺失报错断言；术语守卫 `check:assistant-copy` PASS；backend/引擎全量门禁绿；真机卡片显示"目标空间：默认空间（id：…）+ 目标清单"。

## 未决与非任务事项（不建任务）

> 只放**不属于任务**的东西：待办前置、待人工验收、按规则 13 判为"已知限制并关闭"的项。已关闭项不再挂账；要做的时候才开任务。已关闭/已收口的历史全文见 `docs/plans/archive/2026-09-未决与关闭事项.md`。

- **D2 引擎 `CorsConfig` 收紧（缓解未修）**：`allowCredentials(true)` + `allowedOriginPatterns("*")` 对所有站点反射 Origin 且允许带凭据；D1 回环绑定后远程浏览器够不到它，但**本机进程仍可利用**，不算已修好。
- **数据卫生：逻辑删除的历史残留未清（另开任务才做）**：`user` 377 行 / `space` 206 行 / `picture` 362 行（`isDelete=1`）——对应用不可见，但占库、且**唯一索引仍被占用**（同名词条/账号不可再注册）。清理要跨表物理删除，属独立任务。已清记录见 `docs/plans/records/probe-data-purge-2026-09-15.md` 与 `backend/sql/cleanup/2026-09-15-purge-probe-data.sql`。
  - 规则沉淀：① MySQL 的 `LIKE` 不支持 `[_]` 字符类，精确前缀一律用 `left(col,N)='...'`；② 不能照账号名猜是不是探针（`1234`/`admin` 是带 144/10 张图的在用账号），删前必须看内容足迹。
- **A/B 基线选取口径待用户拍板**：`tag_category` 自 T5 起查词表、已不是空跑接口，两轮 A/B 均出现"最慢基线高于被测路径"使 `net` 列算 0 或反向；`compare-ab.py` 未改。数字与明细见 `backend/jmeter/README.md` §5.6 与 `docs/plans/records/ab-first-run-2026-09-18.md`。
- **助手范围门卫历史污染误拒（2026-09-28 用户拍板先排期，开工前先立字据）**：v2.6-flash 下门卫分类器被会话历史中的固定拒绝轮带偏（同一 in-scope 句新会话过、同会话积累 3 条拒绝轮后被拒），且被拒轮 USER 行入库而 ASSISTANT 行缺位（回放"问了没答"）。改进方向已调研定案：**借鉴 NeMo Guardrails 两点——门卫输入瘦身（不裸吃全量历史）+ 拒绝补为对话一等公民**，不引入护栏框架本体。调研与候选表见 `docs/designs/2026-09-28-助手范围围栏开源调研.md`；现象与根因见 `docs/features/F11-AI助手MVP.md` 已知限制 14 与 `docs/plans/records/T36-T39-助手快赢包.md` 冒烟章节。排在 T27.4+ 写确认门之后的候选队列（客服问答同队列），开工顺序届时由用户定。

## 归档

- 存量修复批次 T1–T3.11（2026-09-12~13）→ `docs/plans/archive/2026-09-存量修复批次.md`
- AI 助手一期 T4–T26（2026-09-13~16）→ `docs/plans/archive/2026-09-AI助手一期.md`
- 安全加固与优化批次 T27–T42（2026-09-16~10-01，T27 写确认门 2026-10-01 部署上线）→ `docs/plans/archive/2026-09-安全加固与优化批次.md`
- 未决与关闭事项全文（2026-09-15~17）→ `docs/plans/archive/2026-09-未决与关闭事项.md`
