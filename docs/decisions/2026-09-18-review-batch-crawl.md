# 2026-09-18 独立 review — T28/T29/T30 批量抓取（规则 13）

> 状态：**有条件通过**，两条前置条件均已处理。口径表行见 `docs/decisions.md` 2026-09-18。复核范围与判据另见 `docs/handoff/0030-2026-09-18-T28批量抓取批量化.md`。本文件写定后不可变（勘误除外）。

## 形态与范围

- **独立上下文**的只读会话（不共享实施会话的推理与自述，明确要求"不采信自述"），**只读约束**：不改仓库、不提交、不执行 DDL/DML（只允许 `SELECT`），但允许跑测试与断言脚本做独立复核。遵守情况：全程只读，未创建/修改/删除仓库内任何文件。
- 复核对象：提交 `be17d93`（T28 批量抓取落库批量化）、`2052e3f`（T29 URL 导入按源地址 + 空间判重）、`27280df`（T30 批量抓取页 0 张显示修正）。
- 判据：设计、边界、安全、与 `docs/spec.md` 关键三行及 F3 讲解的一致性，以及"测试是否真的证明了它声称的东西"。

## 结论：有条件通过

前置条件（两条，均已处理）：

1. **①-1 给 `sourceUrl` 补长度前置校验** → 已修，T31（提交见 `docs/plans/records/T31-源地址长度校验.md`）：红证据是 `DataIntegrityViolationException: Data too long for column 'sourceUrl'`（证明原实现"先转存 COS 再在 INSERT 撞墙"），修复在 `preparePicture` 入口 fail fast，单张与批量共用。
2. **①-3 真机冒烟 0 张路径（T30 的浏览器验收缺口）** → 已交付用户执行（服务由实施会话起：前端 5173 + 后端 8123）。

## ① 现在修（review 判定）

| # | 判定 | 处置 |
|---|---|---|
| ①-1 | `sourceUrl` 无长度校验 × 整批原子 = 整批 500 且可永久复现（P2） | **已修（T31）**，红→绿见记录 |
| ①-2 | prod 的 `rewriteBatchedStatements=true` 该给结论，别成"每年提一次"（P2，零代码） | **保持既有用户决定**（"先本地 + example，prod 随下次发布"），已作为发布硬顺序第 ② 步钉在 `deploy/prod-checklist.md` §2.1；prod yaml 不入库，review 无法验证现状 |
| ①-3 | 跑一次 0 张路径真机冒烟（P2，2 分钟） | **交用户执行**（浏览器路径无法由会话代跑）；结果按规则 16 回填 T30 记录 |
| ①-4 | 文档订正：批量集成测试实测 6 例 | **不改**：`docs/features/F3-批量抓取.md` 已写 6 例（正确）；`docs/plans/records/T28-*.md` 的"5 例"是当时的快照，records 写定不改 |

## ② 判为已知限制并关闭（P3，已写入 `docs/features/F3-批量抓取.md` 的「已知限制」）

1. 前端断言只覆盖"响应 → 展示状态"纯函数，**不覆盖 axios 装配**（拦截器契约由全仓 36 处同样用法与类型检查间接保证，无专门断言）。
2. 批量补偿不区分"回滚是否成功"：极端情况（ROLLBACK 前连接断掉）可能删掉仍被引用的 COS 对象；已有按 URL 引用计数兜底，概率极低。
3. `count` 只校验上界：`0`/负数被解析器短路成空列表，前端显示"没有新增图片…"，实际是"根本没抓"。
4. 回退旧 jar 会静默丢来源地址：旧实体不写该列 ⇒ 这些行退化成"历史行"（不参与判重、会被重复导入一次、地址已丢）；读接口与文件上传不受影响，回退本身安全。

现有「已知限制」**分类经逐条核对正确**：没有"该现在修却写成已知限制"，也没有该写未写（除上面新增的 4 条）。两条特别说明：

- **「抓取外站是 SSRF 面（缓解未修）」的降级写法准确**：`URLFilePictureUpload` 已有逐跳 DNS 重解析 + 内网/保留段黑名单 + http 直连已校验 IP 并保留 Host 头 + https 逐跳校验 + 统一文案消除探测 oracle；残留面确为"本机/内网进程仍可利用"，且本次改动未扩大它（批量入口仍 `@AuthCheck(mustRole = ADMIN)`）。
- **`Com_insert` 增量 = 1 这条断言不是自证**：review 做了负向控制——去掉 `rewriteBatchedStatements=true` 后同一用例 `expected: <1> but was: <3>` 直接红；恢复后连跑 3 次独立 JVM 全绿。

## 已验证清单（review 实跑，可复跑）

1. 三个提交的改动面与 `getByUrl` 删除范围（全仓无残留定义/调用）。
2. `mvn -B test -Dtest='PictureBatchUploadIntegrationTest,PictureSourceUrlDedupeIntegrationTest'` → **12/12 绿**。
3. 回归集合 8 类 → **32/32 绿**（与 T29 记录一致）。
4. CI 口径子集 → **77/77 绿**；日志回读到 `rewriteBatchedStatements=true`、`map-underscore-to-camel-case=false`、`logic-delete-field=isDelete`。
5. `Com_insert` 负向控制（见上）。
6. **SQL 日志实测 `sourceUrl` 语义**：INSERT 含 `sourceUrl`；替换时 `updateById` 的 SET 列表**不含** `sourceUrl`（证明"显式清空"那段必需）；`UPDATE picture SET sourceUrl=?` 确实执行。
7. 文档/口径/`deploy/prod-checklist.md` 表述与代码一致。
8. 前端 `check:batch-crawl-result` → PASS；`type-check` → **124 = 基线**。
9. 只读 DB 核查：列 `varchar(1024)`、索引前缀 191、`sql_mode` 含 `STRICT_TRANS_TABLES`、**重复 (sourceUrl, spaceId) 组 0**、`EXPLAIN` **走 `idx_sourceUrl`**。
10. 暴露面静态核对：批量入口仍 ADMIN；`PictureVO` 无 `sourceUrl`（不进 API 响应）；`git grep sourceUrl` 在 `ai/` 无命中；错误文案无新泄漏。

## 我没能验证什么（review 自述）

prod 的一切（`sql_mode`、列是否已加、历史行 NULL 比例、`application-prod.yaml`）；真机浏览器与真实 Bing 抓取（集成测试都替换了抓页）；**并发竞态（全部为代码论证，无实测）**；DDL 重跑与漏执行的报错面；超长 `sourceUrl` 的端到端复现（只实测了配置前提，"整批 500"是论证，T31 的红证据在单张路径上拿到）；`npm run lint` 与 git 门禁；MyBatis-Plus `updateById` 的注解默认策略（改用 SQL 日志实测行为，未逐行读源码）。
