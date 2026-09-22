# Plan — 火山图库（huoshan-picture）

<!-- freshness: { owner: "lcl", reviewed: "2026-09-18" } -->

> 任务粒度：一个任务 = 一次可测试的改动 = 一个 commit。每个任务必须标注允许触碰的文件范围。
> **本文件是索引**：只留活跃任务 + 当前里程碑；实施记录、红绿输出、证据写 `docs/plans/records/`，这里只留指针。已收口里程碑整批结转 `docs/plans/archive/`（见文末「归档」）。
> **预算 ≤120 行**；超了就该结转了（保养清单见 vibe-handoff 的"文档保养"）。

## 活跃任务

- [ ] T27（2026-09-16 后端安全 review；同日用户放行第一阶段前 4 项）**AI 助手后端安全加固**：收口代理凭据重定向泄露、query satoken、SSE 早取消竞态、日志/异常原文外泄、错误体无界读取、凭据名硬编码，以及写工具缺少确定性确认门。
  - **第一阶段前 4 项已完成（T27 整体仍未完成）**：T27.1 凭据与重定向加固；暂时摘掉 `batchEditPictures` / `batchUploadByUrl`；Prompt/工具日志与未知异常脱敏；页面与功能文档同步为只读/建议能力。实施与红绿证据见 `docs/plans/records/T27-first-release.md`。
  - 后续：T27.2 的其余取消竞态/错误体有界读取可继续修；完整写确认协议仍按 T27.4 先立字据，放行后再做 T27.5～T27.8。
  - 边界：不重复处理 F11 已关闭的多行 SSE、缺 `[DONE]`、线程池拒绝空 500、300 秒超时、GET message 日志、频控/长度限制等已知限制；不新增进程/依赖/数据表。
  - 详细任务、文件范围、负向控制、提交拆分与停止条件：见 `docs/plans/2026-09-16-T27-AI助手后端安全加固实施计划.md`。

- [x] T28（2026-09-18 用户批准）**批量抓取落库批量化**：并行线程只做下载 + COS + 构造未落库记录，`allOf` 后一次 `saveBatch` + 一次清缓存；源地址检查提到并行前（一次 `IN` 查询 + 批内去重）；入库阶段整批原子（失败整批回滚 + 补偿删已转存文件）。
  - 用户可见语义变更（已确认）：下载/COS 失败仍逐张跳过并保留"成功 N 张"，但入库失败整批报错。口径见 `docs/decisions.md` 2026-09-18 行；讲解见 `docs/features/F3-批量抓取.md`。
  - 实施记录与证据见 `docs/plans/records/T28-批量抓取批量化.md`。

- [x] T29（2026-09-18 用户批准：方案 1 + 按空间）**URL 导入按源地址 + 空间判重**：新增 `picture.sourceUrl` 列（URL 导入/批量抓取写入，文件上传为 null），判重改为"源地址 + 目标空间"；批量抓取的并行前过滤改用同一列；命中仍拒绝并沿用"图片已存在"文案；文件替换时清空旧源地址；删掉永不命中的 `getByUrl`。
  - 部署硬约束：DDL 必须先于新 jar（后端无 Flyway），步骤见 `deploy/prod-checklist.md` §2.1；口径见 `docs/decisions.md` 2026-09-18 行。
  - 实施记录与证据见 `docs/plans/records/T29-源地址去重.md`。

- [ ] T30（2026-09-18 用户批准）**批量抓取页"0 张"显示修正**：后端成功抓 0 张时返回 `{code:0, data:0, message:"ok"}`，而页面判的是 `code === 0 && data`（JS 里 `0` 是 falsy）⇒ 走失败分支，显示成标题"任务执行失败"、描述 "ok"。T29 起"重跑同一搜索词"稳定返回 0，这条路径从罕见变成最容易碰到。修法：把"响应 → 展示状态"的映射抽成前端纯函数（`frontend/src/utils/`，新增 `batchCrawlResult.ts`）并配常驻断言（`frontend/scripts/`，新增 `check-batch-crawl-result.mjs` + npm script，沿用 `check:assistant-format` 的做法）；0 张单独给"完成但没有新增"的文案与中性图标。
  - 用户可见行为变更（属规则 3 ④）：成功但 0 张时不再显示为失败。
  - 边界：不改后端返回结构与文案、不动其它页面、不加依赖（esbuild 已在 devDependencies）。
  - 验收：① 断言脚本先红后绿（红用例 = `data=0` 被映射成失败且描述为 "ok"）；② `npm run type-check` 错误数不超基线 124；③ 改动文件 lint 通过；④ F3 讲解补"0 张文案"与并发窗口已知限制。
  - 实施与红绿证据：完成后落 `docs/plans/records/`（T30-批量抓取页0张显示.md）。

## 未决与非任务事项（不建任务）

> 只放**不属于任务**的东西：待办前置、待人工验收、按规则 13 判为"已知限制并关闭"的项。已关闭项不再挂账；要做的时候才开任务。已关闭/已收口的历史全文见 `docs/plans/archive/2026-09-未决与关闭事项.md`。

- **D2 引擎 `CorsConfig` 收紧（缓解未修）**：`allowCredentials(true)` + `allowedOriginPatterns("*")` 对所有站点反射 Origin 且允许带凭据；D1 回环绑定后远程浏览器够不到它，但**本机进程仍可利用**，不算已修好。
- **数据卫生：逻辑删除的历史残留未清（另开任务才做）**：`user` 377 行 / `space` 206 行 / `picture` 362 行（`isDelete=1`）——对应用不可见，但占库、且**唯一索引仍被占用**（同名词条/账号不可再注册）。清理要跨表物理删除，属独立任务。已清记录见 `docs/plans/records/probe-data-purge-2026-09-15.md` 与 `backend/sql/cleanup/2026-09-15-purge-probe-data.sql`。
  - 规则沉淀：① MySQL 的 `LIKE` 不支持 `[_]` 字符类，精确前缀一律用 `left(col,N)='...'`；② 不能照账号名猜是不是探针（`1234`/`admin` 是带 144/10 张图的在用账号），删前必须看内容足迹。
- **A/B 基线选取口径待用户拍板**：`tag_category` 自 T5 起查词表、已不是空跑接口，两轮 A/B 均出现"最慢基线高于被测路径"使 `net` 列算 0 或反向；`compare-ab.py` 未改。数字与明细见 `backend/jmeter/README.md` §5.6 与 `docs/plans/records/ab-first-run-2026-09-18.md`。

## 归档

- 存量修复批次 T1–T3.11（2026-09-12~13）→ `docs/plans/archive/2026-09-存量修复批次.md`
- AI 助手一期 T4–T26（2026-09-13~16）→ `docs/plans/archive/2026-09-AI助手一期.md`
- 未决与关闭事项全文（2026-09-15~17）→ `docs/plans/archive/2026-09-未决与关闭事项.md`
