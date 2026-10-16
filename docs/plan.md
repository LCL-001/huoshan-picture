# Plan — 火山图库（huoshan-picture）

<!-- freshness: { owner: "lcl", reviewed: "2026-10-16" } -->

> 任务粒度：一个任务 = 一次可测试的改动 = 一个 commit。每个任务必须标注允许触碰的文件范围。
> **本文件是索引**：只留活跃任务 + 当前里程碑；实施记录、红绿输出、证据写 `docs/plans/records/`，这里只留指针。已收口里程碑整批结转 `docs/plans/archive/`（见文末「归档」）。
> **预算 ≤120 行**；超了就该结转了（保养清单见 vibe-handoff 的"文档保养"）。

## 活跃任务

- [ ] T64（2026-10-16 字据；设计、现状根因与验收全文见 `docs/designs/2026-10-16-AI扩图降级为维护态设计.md`）**AI 扩图降级为维护态**：百炼 `image-out-painting` 已于 2026-10-10 官方下线（[公告 118434](https://www.aliyun.com/notice/118434)），官方替代 `qwen-image-3.0` 是指令式编辑、不保扩图语义，语义最接近的 `wanx2.1-imageedit` 的 500 张新人免费额度已于 2026-07-09 过期作废且官方明确不支持补发；用户决定**保留功能、不再付费调用**——backend 加维护门闩 `app.outpainting.enabled`（默认 false）：关闭时扩图两个入口一律返回维护提示，**不调百炼、不扣每日配额、不写幂等键**；维护状态经新增只读端点下发，前端弹窗据此展示维护通知并隐藏生成按钮；恢复只需翻转环境变量，**无需前端重新发版**。不删扩图代码、不动 spec 关键三行。
  - 文件范围：backend `service/impl/PictureServiceImpl.java`、`service/IPictureService.java`、`controller/PictureController.java`、`application.yaml`；frontend `components/ImageOutPainting.vue`、`api/pictureController.ts`、`api/typings.d.ts`；`deploy/prod-checklist.md`；新增维护状态 VO 与服务层单测。
  - 验收：服务层单测先红后绿（关闭时抛维护提示、零百炼调用、零 Redis 触碰；开启时门闩不拦）；既有扩图幂等集成测试补 `enabled=true` 后仍绿；前端 type-check 保持 0、改动文件 eslint 干净；浏览器真机点开扩图弹窗显示维护通知且无生成按钮。

### T55–T63：两日改动 review 修复批次（2026-10-13 用户确认）

> 状态：T55/T56 已收口（见各条收口行），**T57–T63 未开始**；按 T57→T63 执行，先红后绿、逐任务代码提交，文档另提；不新增依赖、进程或表。下列范围均为仓库相对路径，含对应测试；实现须另查生效决定，若改变既有口径先补字据，不在本次计划提交中改决定。
> 文档指针：T57–T58 见 `docs/features/F17-回收站.md`；T59–T60 见 `docs/features/F12-图库助手档3工具与联调.md`；T61–T62 见 `docs/designs/2026-10-12-门卫召回第二轮设计.md`；T63 见 `docs/plans/records/T54-前端API类型对齐Long到String.md`。实施时各新增任务号对应 records（未实施不虚建证据），同步对应功能讲解。

- [x] T55（P1）**回收站共享 URL 引用计数**：正常记录和仍可恢复的回收站记录均阻止 COS 清理，统一守护彻底删除、过期清理、替换与回滚补偿。
  - 文件范围：backend `service/impl/PictureServiceImpl.java`、`service/IPictureService.java`、`service/impl/SpaceServiceImpl.java`、`mapper/PictureMapper.java`、`resources/mapper/PictureMapper.xml` 及回收站/替换/补偿/级联测试；验收：同 URL 两张均入站，删首张不清 COS且另一张可恢复，最后引用删除才清；覆盖过期清理与删空间级联。
  - **2026-10-13 收口**：回收站彻底删除 / 过期清理 / 替换 / 回滚补偿与删空间级联统一改走裸 SQL `PictureMapper#countUrlReferences`（存活行 + 回收站内可恢复行，绕开全局逻辑删除）；新增 4 例集成回归；全量集成 97/97、单测 169/169 通过，commit `c5bc15b` + `218f714`；实施记录与证据见 `docs/plans/records/T55-回收站共享URL引用计数.md`，级联扩展见 `docs/plans/records/T55-补记-删空间级联共享URL.md`。
- [x] T56（P1）**回收站写操作有效会话校验**：恢复、彻底删除与普通删除验证 Sa-Token 有效且与 Spring Session 同属一人，再做逐资源 RBAC；不扩大至全站鉴权重构。
  - 文件范围：backend `manager/auth/`（新增 `LoginSessionValidator`）、`service/impl/PictureServiceImpl.java` 及回收站/会话测试；`controller/PictureController.java`、`service/impl/UserServiceImpl.java` 核查后无需改动；验收：缺失/撤销/身份不一致凭据拒绝，改密/降权后旧会话失败，重新登录及公共/私有/团队正向通过。
  - **2026-10-13 收口**：三个写操作入口先过双登录态一致性校验（`LoginSessionValidator#requireConsistentLogin`，不一致回 40102）再逐资源 RBAC；新增 13 例集成回归，受影响既有测试 40/40；全量集成 110/110、单测 169/169 通过，commit `d512c5d`；实施记录与证据见 `docs/plans/records/T56-回收站写操作会话校验.md`。
- [ ] T57（P2）**恢复配额并发安全**：将配额条件放入事务内原子更新，额度不足使当前图片恢复回滚，不重复记账。
  - 文件范围：backend `service/impl/PictureServiceImpl.java` 与回收站服务/集成测试；验收：串行不足拒绝、并发名额和容量临界点不超限、同图并发只回补一次，失败记录仍在回收站。
- [ ] T58（P2）**回收站 API Long→String 类型对齐**：去除三个回收站接口的 `request<any>`，补 VO/分页/操作结果类型，复用统一大小格式化；不运行 OpenAPI 覆盖生成。
  - 文件范围：frontend `src/api/pictureController.ts`、`src/api/typings.d.ts`、`src/pages/RecycleBinPage.vue`、`src/utils/index.ts` 及相关断言；验收：19 位 id 原样透传、字符串 picSize 与零字节正确，错误类型负向控制、type-check/eslint 通过。
- [ ] T59（P2）**AI 系统事实可信来源**：优先复用可持久化的消息 metadata 区分结果事实，历史隐藏不再仅凭用户可伪造的文本前缀；模型提示须区分来源，不宣称绝对防注入。
  - 文件范围：引擎 `agent/BaseAgent.java`、`agent/HuoshanAssistantSession.java`、`controller/HuoshanAssistantController.java`、`utils/ChatHistoryAssembler.java`、`utils/MessageConverter.java`、`chatmemory/` 必要映射及消息测试；验收：普通前缀消息不隐藏、不获可信标记，真实来源经持久化/回放仍可识别，旧记录兼容策略明确；需改表则停止另议。
- [ ] T60（P2；依赖 T59）**AI 结果 operationId 关联**：回灌携带 operationId/类型/结果与既存计划摘要，保留确认门绑定、单次消费及 best-effort；前端阻止待确认时继续生成新写计划。
  - 文件范围：backend `controller/AiAssistantController.java`、`manager/ai/AiAssistantOperationManager.java`、`manager/ai/AiAssistantProxyManager.java` 及必要结果 DTO/VO；引擎 `controller/HuoshanAssistantController.java`、`agent/HuoshanAssistantSession.java`；frontend `src/pages/AssistantPage.vue` 与对应协议/纯函数测试；验收：既有多卡乱序确认结果可区分，失败可关联，阻止新计划不影响取消/查询和服务端授权。
- [ ] T61（P2）**门卫否定式 IN_SCOPE 解析**：双向处理否定 token，明确范围外不得因先出现被否定的 IN_SCOPE 而放行，含糊输出 fail-closed。
  - 文件范围：引擎 `agent/HuoshanAssistantAgent.java` 与 `agent/HuoshanAssistantAgentScopeTest.java`；验收：not IN_SCOPE→OUT 拒绝、not OUT→IN 放行，原 token 顺序/无 token 回归通过，断言拒绝路径不进工具链。
- [ ] T62（P2）**门卫历史窗口边界**：先截取最近 4 条历史，再按既定角色/字数上限过滤，禁止无限向前凑助手行；沿用 T46 的 USER 锚点与结果事实可见性。
  - 文件范围：引擎 `agent/HuoshanAssistantAgent.java` 与 `agent/HuoshanAssistantAgentScopeTest.java`；验收：连续拒绝轮不拼入过早回答，最近任务锚点/结果事实仍保留，短跟进和范围外负向真机复测，模型实测与确定性窗口断言分开记证据。
- [ ] T63（P3；用户明确批准修复，非挂账限制）**前端字符串数值显式转换**：仅对大小/count 等算术和图表数值显式转换，不对 id 做 Number、不顺手重构。
  - 文件范围：frontend `src/pages/admin/PictureManagePage.vue`、`src/components/analyze/SpaceSizeAnalyze.vue`、`SpaceTagAnalyze.vue`、`SpaceUserAnalyze.vue` 及相关断言；验收：字符串/零/缺值转换回归、图表输入为 number，type-check/eslint/助手格式检查通过。

## 未决与非任务事项（不建任务）

> 只放**不属于任务**的东西：待办前置、待人工验收、按规则 13 判为"已知限制并关闭"的项。已关闭项不再挂账；要做的时候才开任务。已关闭/已收口的历史全文见 `docs/plans/archive/2026-09-未决与关闭事项.md`。

- **D2 引擎 `CorsConfig` 收紧（缓解未修）**：`allowCredentials(true)` + `allowedOriginPatterns("*")` 对所有站点反射 Origin 且允许带凭据；D1 回环绑定后远程浏览器够不到它，但**本机进程仍可利用**，不算已修好。
- **数据卫生：逻辑删除的历史残留未清（另开任务才做）**：`user` 377 行 / `space` 206 行 / `picture` 362 行（`isDelete=1`）——对应用不可见，但占库、且**唯一索引仍被占用**（同名词条/账号不可再注册）。清理要跨表物理删除，属独立任务。已清记录见 `docs/plans/records/probe-data-purge-2026-09-15.md` 与 `backend/sql/cleanup/2026-09-15-purge-probe-data.sql`。
  - 规则沉淀：① MySQL 的 `LIKE` 不支持 `[_]` 字符类，精确前缀一律用 `left(col,N)='...'`；② 不能照账号名猜是不是探针（`1234`/`admin` 是带 144/10 张图的在用账号），删前必须看内容足迹。
- **F17 回收站前端真机 / F18 限流触发：2026-10-13 已完成**（原「待人工验收」两条收口）：F17 浏览器走通「删除 → 回收站列表 → 恢复 → 彻底删除」并核对软删/配额回补/物理删行；F18 对 `POST /api/ai/assistant/ticket` 连打 31 次，第 31 次回 `42900`。结果、未覆盖面与一处环境限制（「彻底删除」按钮点击在本自动化环境未生效、改经同一接口）见 `docs/plans/records/F17-F18-人工验收.md`。
- **A/B 基线选取口径待用户拍板**：`tag_category` 自 T5 起查词表、已不是空跑接口，两轮 A/B 均出现"最慢基线高于被测路径"使 `net` 列算 0 或反向；`compare-ab.py` 未改。数字与明细见 `backend/jmeter/README.md` §5.6 与 `docs/plans/records/ab-first-run-2026-09-18.md`。

## 归档

- 存量修复批次 T1–T3.11（2026-09-12~13）→ `docs/plans/archive/2026-09-存量修复批次.md`
- AI 助手一期 T4–T26（2026-09-13~16）→ `docs/plans/archive/2026-09-AI助手一期.md`
- 安全加固与优化批次 T27–T42（2026-09-16~10-01，T27 写确认门 2026-10-01 部署上线）→ `docs/plans/archive/2026-09-安全加固与优化批次.md`
- 助手安全与回收站收口批次 T27.10、T43–T54（2026-09-29~10-13）→ `docs/plans/archive/2026-10-助手安全与回收站收口批次.md`
- 未决与关闭事项全文（2026-09-15~17）→ `docs/plans/archive/2026-09-未决与关闭事项.md`
