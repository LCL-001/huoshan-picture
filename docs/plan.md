# Plan — 火山图库（huoshan-picture）

<!-- freshness: { owner: "lcl", reviewed: "2026-09-28" } -->

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

- [x] T30（2026-09-18 用户批准）**批量抓取页"0 张"显示修正**：成功抓 0 张时后端返回 `{code:0, data:0, message:"ok"}`，页面判 `code === 0 && data`（`0` 是 falsy）⇒ 显示成"任务执行失败 / ok"。已把"响应 → 展示状态"抽成纯函数 `frontend/src/utils/batchCrawlResult.ts` + 常驻断言 `check:batch-crawl-result`，0 张改为中性提示"没有新增图片…"。
  - 验收：断言脚本先红 5 → 绿；`type-check` 124 = 基线；改动文件 lint 净减；F3 讲解已补 0 张文案与并发窗口已知限制。
  - 实施记录与证据见 `docs/plans/records/T30-批量抓取页0张显示.md`。

- [x] T31（2026-09-18 独立 review 前置条件 ①-1，用户批准）**源地址长度前置校验**：`picture.sourceUrl` 是 `varchar(1024)` 且入库前无长度校验，严格模式下超长 URL 的 INSERT 报 `Data too long`；因为 T28 起入库整批原子，批量里 1 张超长即整批回滚 + 补偿删除 + 接口 500，且 Bing 同词重跑必撞同一张（该搜索词永久不可用）。已在**单张与批量共用的 `preparePicture` 入口**（发起下载之前）按列宽 fail fast 抛 `PARAMS_ERROR / "来源地址过长"`。
  - 落点说明：review 建议的落点（`uploadPicture` 判重块）只覆盖单张路径，批量走 `preparePicture` 绕开那里；放在共用入口后，批量下超长 URL 只让该张按"单张失败"跳过，其余照常入库。
  - 实施记录与证据见 `docs/plans/records/T31-源地址长度校验.md`。

- [x] T32（2026-09-27 用户提出：窗口扩容 + 去 advisor）**记忆读写去 advisor 化 + 预算扩容**：核实 Spring AI `MessageChatMemoryAdvisor` 每轮重读全量记忆注入 + 回写最后一条用户消息（循环每轮重提整段 messageList ⇒ 同段上下文每轮两份、逐轮膨胀），移除 advisor 改显式读写——运行开始读一次压缩视图作前缀（`historyContext`）、写一次用户消息，每步模型调用后写一次助手消息；指纹去重随 advisor 退役；压缩预算 4096 → 32768（主脑 1M 上下文）。
  - 验收：`AgentExplicitMemoryTest` 接线守护 + `FlowWindowBasedChatMemoryAddTest` 写入契约（两项负向控制实测红）；20 轮模拟：4096 对照 65% / 基线 14→7 不变，默认预算 32768 零摘要调用；ai/agent 196 例绿。
  - 实施记录与证据见 `docs/plans/records/T32-记忆显式读写.md`；口径见 `docs/decisions.md` 2026-09-27 行。

- [x] T33（2026-09-27 用户批准：方案 2 不含 2b）**循环检测重写为双信号滑窗**：旧实现照抄 OpenManus 的"assistant 文本全局计数"——空文本工具决策行让检测失明（最常见的"同工具同参数反复调用"抓不到）、全局计数误报合法重复、精确匹配漏报微差。改为 assistant 子序列的"循环签名"（工具调用步 = `tool|名|规范化参数`，纯文本步 = `text|文本`，带文本的决策行按工具签名判）取最近 4 步滑窗，同签名 ≥2 次（含当前）判卡死；第 1 次纠偏、第 2 次强停（MAX_STUCK_COUNT 3→2）；state 已 FINISHED 不再补发"检测到循环"（修双 answer）。
  - 调研对齐：OpenManus（源头同款缺陷）/ LangGraph·OpenAI SDK·CrewAI（只做步数硬边界）/ Strands（工具指纹滑窗 + 执行前阻断）/ Inngest（连续同工具）；"执行前阻断"（2b）经用户拍板暂不做。
  - 验收：`BaseAgentStuckTest` 重写 12 例（先红 7）+ `BaseAgentTest` 双 answer 用例；负向控制两项实测红（摘工具签名 → 3 红；滑窗改全局 → 1 红）；ai/agent 203 例绿。
  - 实施记录与证据见 `docs/plans/records/T33-循环检测双信号.md`；口径见 `docs/decisions.md` 2026-09-27 行。

- [x] T34（2026-09-28 用户批准：只改 Redis 版本号，本地层保持全清）**图片列表缓存失效按空间切分**：全局单版本号改为按 scope 分开（空间图库是该空间 id、公共图库是 `public`），8 处写路径各自只失效自己影响的 scope；本地 Caffeine 层仍按既定取舍全清 + 广播（因此广播报文不带 scope）。
  - 依据：查询的 scope 两两不相交，且图片的空间归属创建后不可变（编辑 DTO 无 `spaceId` 字段、替换时空间不一致直接拒）⇒ 一次写入最多影响一个 scope。
  - 讲解见 `docs/features/F4-图片列表多级缓存.md`；实施记录与证据见 `docs/plans/records/T34-缓存失效按空间切.md`。

- [x] T35（2026-09-28 用户指定）**助手模型升级为 `mimo-v2.6-flash`**（字据）：主脑与 visionTagger 两组同换——官方"图片理解"支持列表含该型号、输入模态含图像，旧口径"图像输入仅 v2.5 支持"已失效；base-url 与关思考模式两条口径不变，零代码改动。
  - 范围：本机三份不入库 yaml（引擎 `local` / `prod`、backend `local`）+ 入库的默认值与示例（`application-prod.yaml.example`、`.env.example`、`backend/src/main/resources/application.yaml` 与 `application-local.yaml.example` 的注释示例）+ 引用同步（`deploy/prod-checklist.md` / F12 / F14）。**线上 prod 不在范围**——型号由服务器环境变量决定。
  - 依据、代价与待验证见 `docs/decisions/2026-09-28-model-upgrade.md`；实施记录与"未做真机冒烟"的缺口见 `docs/plans/records/T35-模型升级.md`；**速度实测补记**（解码 110.3 vs 59.1 tok/s、TTFT 1.4s vs 5.7s，8 次测量无重叠）见 `docs/plans/records/T35-速度实测.md`。

- [x] T36（2026-09-28 字据，设计与全部口径见 `docs/designs/2026-09-28-助手快赢包设计.md`，实施计划见 `docs/plans/2026-09-28-T36-助手快赢包实施计划.md`）**AI 助手会话索引与列表端点**：新表 `ai_conversation`（user_id + chat_id 唯一索引，跟随 is_delete 惯例，SQL 入 backend/sql）；`/api/ai/assistant/chat` 门槛全过后建流前同步 upsert（标题 = 首条消息截断，已存在只刷 update_time 不覆盖标题）；`GET /api/ai/assistant/conversations` 按登录用户分页直出。v1 不做重命名/删除。验收：单测钉 upsert 三语义（首条定标题 / 不覆盖标题 / 刷时间）+ 列表用户隔离 + 未登录 40100（Mockito，不依赖 MySQL）；部署约束：新表 DDL 先于新 jar。
- [x] T37 **引擎只读消息端点 + 代理透传**：引擎 `GET /api/ai/huoshan/conversations/{chatId}/messages`（内部密钥信道；userId 归一化 → 确定性 conversationId → `ChatHistoryAssembler` 组装，空返回空列表；零 DDL 零新依赖）；backend `GET /api/ai/assistant/conversations/{chatId}/messages` 先验索引归属（40400 零上游）再透传（userId 取自登录态，不收浏览器参数）。验收：引擎侧派生/组装/空态断言；backend 侧归属校验零上游 + 透传形状断言。
- [x] T38 **前端会话侧栏 + 历史回放 + 续聊**：桌面左侧栏 / 移动抽屉；点会话拉历史按轮 + steps 渲染（中文别名与摘要复用 `assistantFormat.ts`，详情 = 截断原文）；选中即把 chatId 写回 sessionStorage 续聊；发送成功后重拉列表。新纯函数进 `check:assistant-format` 断言；type-check 净增 0。
- [x] T39 **失败独立呈现**：代理侧合成失败（引擎非 SSE / 连接读取失败 / 流中断）由 answer 改发协议既有 error 帧（仍以 [DONE] 收尾）；前端把 event=error 分流为独立消息类型与错误样式，不再混入助手气泡。引擎侧行为不动（EOF 无 [DONE] 的同屏语义保留，仅呈现形态变化）。验收：代理帧形状断言 + 前端分流断言。
  - 四任务实施记录、提交清单与门禁数字见 `docs/plans/records/T36-T39-助手快赢包.md`（子代理驱动执行，每任务一笔 commit：`e8551cc` / `97116ba` / `bc021b8` / `1dc503f`）；**真机冒烟已过**（同记录：`local,test` 双 profile 全链路 + DB 行序列核对，冒烟修复 `03b2450`），spec F15 已勾；引擎 jar 未重新部署（T32/T33/T37 同车）。

> 勘误（2026-09-28 计划期）：原 T40（visionTagger/searchImage 工具摘要分支）经核实**已实现且断言入库**（`frontend/scripts/check-assistant-format.mjs`），撤销；F12 已知限制 5 系陈旧条目已订正。

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
