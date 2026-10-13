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

- [x] T46（2026-10-12 字据批准；来源 = 同日规则 13 独立 review 的 P1+P2；设计见 `docs/designs/2026-10-12-门卫召回第二轮设计.md`）**范围门卫召回第二轮**：门卫输入改为纳入历史尾巴的 USER 文本（含 T44「操作结果」事实行）——此前 USER 行只作轮边界、文本不可见，导致"写操作已完成"的追问被误拒；移除生产恒空的工具名死信号（回放视图剥掉 `toolCalls`）；判定解析健壮化（否定式前言守卫 + 能力枚举补写/编辑操作）。保持 fail-closed 与"门卫是语义策略、非授权边界"不变；**修订 T43 A1 的"USER 行不进输入"**（拒绝话术仍排除）。详见 `docs/decisions/2026-10-12-review-confirm-gate-and-scope-gate.md`。
  - 文件范围：引擎 `agent/HuoshanAssistantAgent.java` + `agent/HuoshanAssistantAgentScopeTest.java`。不碰 backend、前端、BaseAgent 循环本体。
  - 验收：单测先红后绿（写操作追问的锚点进输入；门卫输入是回放代表性的、无工具名假信号；否定式前言不否决 IN_SCOPE；范围外仍 fail-closed）；负向控制实测红；引擎门禁全绿；真机复跑误拒形态。
  - **2026-10-12 收口**：引擎先红 3 例（即窗口负向控制）→ 14/14 绿；提交门禁全量 backend 169 / 引擎 215 全绿，commit `371c661`；真机复跑——修复前被误拒的写操作追问原话已放行、同会话范围外请求仍拒；实施记录与证据见 `docs/plans/records/T46-门卫召回第二轮.md`。

- [x] T48（2026-10-12 用户批准；**推翻** 2026-09-13「集成测试不入 CI」决定——该行状态改已废弃）**CI 跑集成测试**：workflow 加 mysql:8 与 redis:5 service 容器（Redis 5.x 对齐仓内命令约束）、按序灌 `backend/sql/` 下 create_table / create_tag_table / create_ai_conversation 三个脚本、新增"集成测试"步骤跑 `*IntegrationTest` + `RedisStringTest` + `YunPictureBaseApplicationTests`；pre-commit 快速子集不动（规则 12）。
  - 文件范围：`.github/workflows/ci.yml`；`AGENTS.md` 命令节措辞订正（pre-commit 快速子集 / CI 含集成测试）。
  - 验收：本机真 MySQL/Redis 跑该 `-Dtest` 全绿（前置，红则按规则 5 停）→ CI 在 PR 上绿；文档一致性门禁绿。
  - **2026-10-12 收口**：本机集成 **86/86**（修复前 1 失败＝一处过期断言，T42 漏改集成测试所致，已修）、CI 模拟 `local,ci` 86/86、全新库建表探针 11 表全建；commit `898c22e`；记录见 `docs/plans/records/T48-CI集成测试.md`。

- [x] T49（2026-10-12 用户批准）**接口限流**：新增 `@RateLimit` 注解 + 切面（复用 StringRedisTemplate、键 huoshantuku:ratelimit:*、INCR + 惰性 TTL、超限 42900、Redis 异常 fail-open、总开关 `app.rate-limit.enabled`）；只打高成本/敏感端点（助手 chat / 取票 / prepare / confirm、扩图、AI 打标、批量抓取、URL/文件上传、以图搜图）。
  - 文件范围：backend `ErrorCode.java`、新增 `annotation/` 注解与 `aop/` 切面、`controller/` 受保护端点、`application.yaml`；新增两个测试类。
  - 验收：切面单测先红后绿（恰好 limit 放行 / limit+1 抛 42900 / 关开关零 Redis / Redis 异常 fail-open / 键含 principal）+ 端点覆盖守卫；backend 全量门禁绿。
  - **2026-10-12 收口**：切面 6 例 + 覆盖守卫 1 例绿（负向控制 2 处命中：边界改坏、摘注解），commit `06770d6`；讲解见 `docs/features/F18-接口限流.md`。

- [x] T50（2026-10-12 用户批准）**回收站后端**：picture 加 deleteTime 列（DDL + 索引）；`deletePicture` 改软删（isDelete=1 + deleteTime，**不再即时删 COS**）；新增列表/恢复/彻底删三个能力——裸 SQL mapper（本仓首处自定义 SQL）、恢复逐张复核权限 + 空间配额回补 + 清缓存、彻底删按引用计数删 COS 后物理删行。
  - 文件范围：backend `Picture.java`、`mapper/` 与 `resources/mapper/`、`IPictureService.java` 与 `PictureServiceImpl.java`、`PictureController.java`；新增 DDL 脚本与集成测试。
  - 验收：service 单测先红后绿（配额回补 / 超额拒 / 越权拒 / 清缓存 / purge 引用计数）；`PictureTrashIntegrationTest` 真库全绿（删→列表可见→恢复→计数回补→彻底删→行消失 + COS 清理）——随 T48 进 CI。
  - **2026-10-12 收口**：单测 7 例 + 集成 6 例绿（负向控制命中配额两条）；全量集成 92/92，commit `f3d64f4`；讲解与已知限制见 `docs/features/F17-回收站.md`。

- [x] T51（2026-10-12 用户批准）**回收站保留期清理**：`@Scheduled` 组件分批扫 `deleteTime < now - app.trash.retention-days`（默认 30）后复用彻底删逻辑；异常不外溢。
  - 文件范围：backend 新增清理组件、`application.yaml`（app.trash.retention-days）；新增单测与真库集成测试。
  - 验收：单测（边界 / 过期集合 / 异常不中断 / 分批）先红后绿；真库集成（造过期行 → 跑一轮 → 行物理消失、COS 被清）。
  - **2026-10-12 收口**：任务单测 2 例 + 保留期真库集成 1 例绿，commit `4912479`；讲解见 `docs/features/F17-回收站.md`。

- [x] T52（2026-10-12 用户批准）**回收站前端**：新增回收站页 + 路由 + 侧栏菜单项 + 手写 API（不跑生成器，decisions 2026-09-15-topic10）；删除确认文案订正为"已移入回收站，可恢复"。
  - 文件范围：frontend `pages/`（新增页面）、`router/index.ts`、`components/GlobalSider.vue`、`api/pictureController.ts`、删除确认所在组件。
  - 验收：`type-check` 不净增基线、改动文件 eslint 干净、`check:assistant-format` 不破；本机真机走通删除→回收站→恢复→彻底删。
  - **2026-10-12 收口**：type-check 124＝基线、新页面 eslint 干净、assistant-format PASS，commit `befbbe3`；讲解见 `docs/features/F17-回收站.md`。**未做**浏览器人工验收（记为 F17 已知限制 7）。
  - **2026-10-13 补记（用户发现的缺陷，已修）**：入口当年落进了**死组件** `components/GlobalSider.vue`（全库零引用），现役侧栏 `layouts/BasicLayout.vue` 里没有回收站 ⇒ 界面上无入口、只能手输 URL。已把菜单项补进 `BasicLayout.vue` 的固定菜单并补图标导入，**并按用户要求同时加进顶部主导航**（`GlobalHeader.vue` 的 `originItems`）；type-check / eslint 干净、浏览器点侧栏与顶部导航均可跳转并高亮。实施记录与证据见 `docs/plans/records/F17-F18-人工验收.md` 补记节。

- [x] T53（存量收敛；按规则 3 **不立字据**，2026-10-12 实测后开条目）**前端存量报错清零**：`npm run type-check` 124 → 0、`npx eslint .` 60错+1警 → 0（30 个 src 文件 + 1 个 node_modules/vue-cropper）。多为存量债；最大单点 = 生成 api 的 `requestType: 'form'` 不在 `AxiosRequestConfig` 里、致 axios 重载失配、`request<T>` 的 `T` 悬空（连带 15 错）。逐批一个 commit（B1–B7，见下），收口时把 `frontend/type-check-baseline.txt` 的 `errors` 下调到 0。
  - 文件范围：frontend `src/**`（`api/`、`components/`、`pages/`、`stores/`、`utils/`、`constants/`）+ 新增 `src/types/vue-cropper.d.ts`；`frontend/type-check-baseline.txt`；`AGENTS.md`、`docs/decisions.md`、`docs/features/F11-AI助手MVP.md` 的口径订正；`docs/plans/records/T53-*.md`。
  - 验收：`npm run type-check` 0 错、`npx eslint .` 0 错 0 警；文档一致性门禁绿（基线 errors=0）；`check:assistant-format` / `check:assistant-copy` 不破。
  - 分批：B1 生成 api 层与全局类型（去 `requestType` 污染与无用 `// @ts-ignore`、补 `UserVO.email`、`SpaceRankAnalyzeRequest`、vue-cropper shim）→ B2 analyze 组件 → B3 图库组件 → B4 空间页 → B5 图片页 → B6 管理页与常量 → B7 边角与纯 eslint。
  - **2026-10-12 收口**：B1–B7 逐批提交，`npm run type-check` 124→**0**、`npx eslint .` 60错+1警→**0 错 0 警**，基线 `errors=0`；`check:assistant-format`/`check:assistant-copy`/`check:batch-crawl-result` PASS、`npm run build` 成功；各批门禁 backend 169 / 引擎 215 全绿。**未做浏览器人工回归**（记入 records）。实施记录与证据见 `docs/plans/records/T53-前端存量报错清理.md`。

- [x] T54（2026-10-13 用户批准；口径与影响面见 `docs/decisions/2026-10-13-api-types-long-to-string.md`）**前端 API 类型对齐后端 Long→String**：把 `frontend/src/api/typings.d.ts` 里恒为 int64 的字段（全部 id / spaceId / pictureId / 分页 total 等）由 `number` 改 `string`，落实 2026-09-14「id 按字符串透传」与 topic10「雪花 id 按 string 声明」；生成器 `openapi.config.js` 加 `hook.customType`（int64→string），但**不自动重生成**（2026-09-15-topic10 不变）。顺带删掉因类型收紧而暴露的 5 处 `Number(id)` 强转——图片详情页 `40400` 的根因（T53 为过 `vue-tsc` 引入）。
  - 文件范围：frontend `src/api/typings.d.ts`、`openapi.config.js`、因类型收紧需调整的 `src/**`；`docs/plans/records/T54-*.md`。
  - 验收：类型收紧后 `type-check` 先红（49 错 / 22 文件）→ 修完回 0；`npx eslint .` 0 错；backend / 引擎门禁不破；浏览器真机复验图片详情页不再 40400。
  - **2026-10-13 收口**：typings 定向改写 120 行；`type-check` 49 错 → **0**、`npx eslint .` 0 错、三个 check 脚本 PASS、负向控制（回注 `Number(id)` 报 1 错）；真机图片详情页（`/picture/2102421103233830914`）正常渲染、无 40400；全站 15 条路由人工回归通过（含三个管理页，用临时提权的探针账号，验完还原）、AI 打标建议生成与 URL 上传/批量编辑写路径实测通过（测试数据已彻底清理），另修 `formatSize` 零字节显示（`c977de3`）。字据 `cff90c2` + 代码 `dbc77ae`；实施记录与证据见 `docs/plans/records/T54-前端API类型对齐Long到String.md`。
  - **2026-10-13 独立 review（规则 13，只读）：有条件通过**。3 条 P2 已在本任务内修毕（`typings.d.ts` 的 `BaseResponseLong_.data` 与 `SpaceCategoryAnalyzeResponse.count` 两处漏改 + 连带 `GlobalHeader.vue`；记录与 plan 的矛盾按勘误订正），2 条 P3 关闭（Post 停用模块的死类型字段分裂、口径数字不精确）。复核范围与「没能验证什么」见 handoff 0036，结论见 `docs/decisions.md` 同日行。

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
- 未决与关闭事项全文（2026-09-15~17）→ `docs/plans/archive/2026-09-未决与关闭事项.md`
