# Plan — 火山图库（huoshan-picture）

> 任务粒度：一个任务 = 一次可测试的改动 = 一个 commit。每个任务必须标注允许触碰的文件范围。
>
> 2026-09-13 **AI 助手一期立项**（设计文档 docs/designs/2026-09-13-图库智能体一期设计.md）：**同日 R2——MyManus 引擎与 image-search-mcp-server 整体迁入本仓库（ai/ 独立应用），一期全部任务在本仓执行（T4-T12，另 T13 可选）**。

## 任务清单（AI 助手一期，2026-09-13 立项，R2 单仓化）

- [x] T4 AI 引擎迁入（2026-09-13 完成）：ai/agent（端口 8124）与 ai/image-search-mcp-server（8127）原样迁入；顺带修复 ImageSearchTool.java UTF-8 BOM 编译错误（源仓遗留）；ai/agent 55 用例基线在新位置全绿；门禁已纳入 ai/agent 单测（负向探针验证会拦）；双服务冒烟通过（8124 /api/health → ok、8127 SSE 启动正常）；源仓库 yu-ai-agent 已归档（handoff 2026-09-13-引擎迁出归档.md + tag archive/engine-migrated-to-huoshan；用户 README 手改已代提交 8a5f450）。实施计划：docs/plans/2026-09-13-T4-AI引擎迁入实施计划.md（迁移提交 88b50bf / f1c4a1a / 4560fd2）
- [x] T5 标签词表（2026-09-14 完成）
  - 文件范围：backend/sql 新建表脚本、domain（Tag）、mapper、service、PictureController.listPictureTagCategory、PictureServiceImpl 编辑两处
  - 验收：集成测试（动态词表返回/种子迁移/upsert 计数/返回结构兼容）+ 门禁绿 + 全量回归
  - **实施记录与证据：见 `docs/plans/records/T5.md`**
- [x] T6 OpenAI 协议模型接入（2026-09-14 完成）
  - 文件范围：ai/agent 的 pom、application.yaml（+example）、模型装配 config
  - 验收：单测（装配/配置绑定）+ 本地冒烟
  - **实施记录与证据：见 `docs/plans/records/T6.md`**
- [x] T6.1（P1，T7/T8 开工前必做，2026-09-14 完成）流式路径超时保护（**两件事，缺一不可**）
  - 现象：`OpenAiApi.Builder` 无参构造默认 `webClientBuilder = WebClient.builder()`（字节码），而 `OpenAiChatModels#build` 只调 `restClientBuilder(...)`
  - 影响：T8 把对话循环收敛为"仅 SSE 流式"后，上游一次卡死即永久挂住 SSE 连接与请求线程（TCP 不断开、无字节）；且不产生异常，Spring AI 重试模板（仅 retryOn `TransientAiException`）无从介入
  - 验收：单测（阻塞路径 requestFactory 超时 + 流式 connector 超时 + 流空闲超时三处断言）+ 冒烟（无响应 / 吐一半静默两种形态均在超时内失败，且稳态长流不被误杀）
  - 文件范围：ai/agent 的 config/OpenAiChatModels.java + 对应测试（②若落在 T8 事件循环实现，文件范围随之加 T8 的循环文件）
  - **实施记录与证据：见 `docs/plans/records/T6.1.md`**
- [x] **MVP 档位与推进顺序（2026-09-14 用户拍板"先做出来能用，再慢慢优化"）**——一期范围不变，只调顺序与颗粒度：（**2026-09-15 结案**：档 1/2/3 全部完成；本条目此前有意留未勾，是因为它承载的 R1 处置"延后到档 2 之后"待用户决策——该决策已由 T23 于 2026-09-15 拍板"接受"+ 落地埋点，故此处补勾）
  - **档 2 用户可见 MVP（本轮终点）**：T10（backend 代理：服务间 API key + 凭据组透传（satoken + 会话 Cookie，B 口径）+ SSE 转发）+ T11 最小形态（助手入口 + 对话面板 + SSE 步骤折叠条）。验收：登录后在界面里对话、折叠条逐步可见；收口时做一次里程碑独立 review（规则 13）+ 一篇合并的功能讲解（规则 9）。
  - **档 3 完整一期**：T7 的 visionTagger / `batchEditPictures` / `batchUploadByUrl`、T9（MCP 搜图）、T8 的循环合并（T8-hard：统一 run/runStream、`run()` 退役、事件协议显式契约）、T12 两条业务场景联调、T13 清理。
  - **转向原因与口径记入 decisions.md（2026-09-14）；旧任务一条不删，档 3 照旧挂在清单里。**
  - **实施记录与证据：见 `docs/plans/records/mvp-phases.md`**
- [x] T7 图库工具集（ai/agent）
  - 档位拆分（见上"MVP 档位"）：前三个只读工具属**档 1**；visionTagger 与两个批量工具属**档 3**。**档 1 部分已于 2026-09-14 完成**（`tools/huoshan/` 三工具 + `HuoshanApiClient` + `HuoshanToolFactory`，桩 HTTP 单测进门禁），visionTagger 与两个批量工具仍挂档 3
  - 文件范围：ai/agent 的 tools 新增包、必要 config/DTO
  - 验收：单测（Mock 图库 HTTP + visionTagger 打标解析）进门禁绿
  - **实施记录与证据：见 `docs/plans/records/T7.md`**
- [x] T8 会话类型机制 + headless 端点（ai/agent）
  - 文件范围：ai/agent 的 BaseAgent/ReActAgent/ToolCallAgent 循环重构、agent 装配类、controller 新增端点、config、受影响测试迁移
  - 验收：单测（鉴权/身份映射/工具装配/事件协议契约）+ SSE 端点冒烟；门禁绿
  - **实施记录与证据：见 `docs/plans/records/T8.md`**
- [x] T9 MCP client 接入（ai/agent）（2026-09-14 完成）：spring-ai-starter-mcp-client（已在 pom）连接 ai/image-search-mcp-server（**传输方式定为 SSE**）；Pexels 搜图工具进图库助手工具集；MCP 服务本体零改动
  - 文件范围：ai/agent 的 application.yaml、MCP client 配置/装配
  - 验收：单测 + 本地双进程冒烟（搜图返回 URL 列表）
  - **实施记录与证据：见 `docs/plans/records/T9.md`**
- [x] T10 图库后端 AI 代理模块（2026-09-14 完成）
  - 文件范围：backend 的 controller 新增代理端点、service/manager 新增代理与转发组件、config、application-local.yaml.example
  - 验收：集成测试（API key 校验/凭据组透传，含"只带 satoken 不带会话 Cookie 时引擎拒绝"的负向用例）+ 与 ai/agent 冒烟联调
  - 注意：凭据只在请求生命周期内存流转、不落库、不进日志（与引擎侧同口径）
  - **实施记录与证据：见 `docs/plans/records/T10.md`**
- [x] T11.1 助手界面可用性打磨（2026-09-14 用户实机使用后当场提出并当日完成，属 T11 的返工打磨）
  - 文件范围：frontend/src 的 components/assistant/*、utils/assistantFormat.ts、pages/AssistantPage.vue
  - 实现口径：**不引 markdown 库、不用 `v-html`**——自写子集解析 + Vue 模板插值（转义交给 Vue），从根上不留 XSS 面（回答文本可被工具数据/提问影响，绝不能当 HTML 渲染）；摘要解析失败一律退化成"已完成"，不抛错、不空屏
  - **实施记录与证据：见 `docs/plans/records/T11.1.md`**
- [x] T11 前端助手界面（2026-09-14 完成）：AI 助手入口 + 对话面板 + SSE 步骤折叠条（消费 ai/agent 步骤事件协议语义，AntD 组件渲染）；复用现有登录态
  - 文件范围：frontend/src 新增助手页面/组件、路由入口、openapi 生成代码如需
  - 验收：npm run type-check + lint 过；对话与折叠条人工验收
  - **实施记录与证据：见 `docs/plans/records/T11.md`**
- [x] T12 一期联调验收（2026-09-14 完成）：跑通两条场景（智能整理/选图入库）；按规则 9 写功能讲解文档 docs/features/
  - 文件范围：联调发现的必要小修 + docs/features/
  - 验收：设计文档"验收标准"4 条全过；门禁绿 + 全量回归
  - **实施记录与证据：见 `docs/plans/records/T12.md`**
- [x] T13 引擎教学遗留清理（承接原 yu-ai-agent T1）（2026-09-14 完成）：AuthAdvisor 假实现、demo 包、空壳控制器（ChatMessage/ChatSummary）、FileBasedChatMemory 死代码、空目录
  - 文件范围：ai/agent 内上述文件与目录
  - 验收：编译 + 门禁绿
  - **实施记录与证据：见 `docs/plans/records/T13.md`**
- [x] T14（2026-09-15 独立 review 的 P2）**助手能力口径回填**
  - 文件范围：`frontend/src/pages/AssistantPage.vue`（仅页面文案两处）+ `docs/features/F11-AI助手MVP.md`
  - 验收：① 页面文案与引擎侧系统提示词同口径——可读可改、**改动只在用户明确要求时发生**、不支持删除；② F11 的只读表述（一句话 / 怎么用 / 核心流程图）全部回填、「已知限制 12」改写为已收口；③ `npm run type-check` 净增 0（基线 138、改动文件 0 命中）、改动文件 eslint 0 错
  - 不做：示例问题不新增写类样例（三条只读样例不算错误声明，属可选增强）；前端错误态 UI、工具摘要增强仍按 F12 已知限制挂着
  - **实施记录与证据：见 `docs/plans/records/T14.md`**
- [x] **同批 review 的 3 条 P3（2026-09-15 T21 全部收口，提交 `e4f09a5`；本行原先标注"未做，可穿插到任何一次会碰这些文件的任务"）**
  - **实施记录与证据：见 `docs/plans/records/review-p3-closure.md`**
- [x] T15（P0，backend）**AI 打标服务（管理员手动触发，2026-09-15 用户拍板
  - 不做：定时任务/阈值扫描；`picture` 表新增 `ai_tag_*` 列（无自动扫描即不需要，将来若要自动跑再迁移）；不做每日配额（管理员手动=天然限流，单次上限已拦）；不动助手侧（见 T17）
  - **实施记录与证据：见 `docs/plans/records/T15.md`**
- [x] T16（frontend）**管理端 AI 打标入口**：公共图库管理页给管理员加"AI 打标"入口——选图 → 跑建议（可编辑，仿 `BatchEditPictureModal` 的形状）→ 确认落库；普通用户完全看不到入口
  - 文件范围：`frontend/src/pages/admin/PictureManagePage.vue`（或对应管理页）、`frontend/src/api/pictureController.ts`（`npm run openapi` 生成）、必要时复用/仿 `components/BatchEditPictureModal.vue`
  - 验收：管理员可见可用、非管理员无入口；`type-check` 净增 0（基线 138、改动文件 0 命中）、改动文件 eslint 干净
  - **实施记录与证据：见 `docs/plans/records/T16.md`**
- [x] T17（助手侧收口，2026-09-15 用户拍板"普通用户助手里的只读 visionTagger 摘掉"）**按角色裁剪打标工具 + 是否复用 T15 的接口**：普通用户的助手不再挂 `visionTagger`；管理员若保留助手里的打标能力，工具应改为调 T15 的出建议/应用接口，避免提示词与词表注入两处维护
  - 文件范围：backend 代理（透传调用者角色，如 `X-User-Role`，走服务间密钥那条信道）、`HuoshanAssistantController`、`HuoshanToolFactory`
  - 验收：普通用户会话的工具集不含打标工具（装配断言）；管理员会话可用；门禁绿
  - **未做（本轮不碰，用户未拍板）**：把管理员侧工具改调 T15；打标提示词目前仍在引擎与 backend 各存一份（T15 起如此，已记 decisions 与 F12「已知限制」）。
  - **实施记录与证据：见 `docs/plans/records/T17.md`**
- [x] T18（2026-09-15 用户拍板参数）**LLM 调用的失败时限收口**：把"provider 不可达/卡死"这一类失败从"约 19 分钟才失败、且用户被裸断连"改成"有时限、有文案"。
  - ② **看图对齐 T15 的"层次一"**：`VisionTaggerTool` 从"逐张串行、单张上限 120s（HTTP 超时）、一张卡住整批卡住"改为有界并发 4 + 单张 45s 墙钟预算 + 单张超时/失败只降级该张 + 结果按输入顺序；登录态失效的"提前停止"从**逐张**粒度变为**逐波**粒度（并发下无法逐张保证，见验收）。
  - 文件范围：ai/agent 的 `config/OpenAiChatModels.java`、`tools/huoshan/VisionTaggerTool.java` + 对应测试（`config/OpenAiChatModelsRetryTest.java` 新增、`tools/huoshan/VisionTaggerToolTest.java` 扩改）
  - **未做（用户未拍板）**：引擎 emitter 仍是 300s、与代理的 300s"赛跑"，超时形态仍可能退化成裸断连（本任务只把失败时长压到它以内）；R1 只做记录订正，不换 connector。
  - **T18 补记（2026-09-15，收口 T19 会话遗留的"时间敏感用例"挂账）**：只改 `OpenAiChatModelsRetryTest` 里"总预算真的会截断"这一条的**测试参数与注释**，生产常量（45s / ≤4 次 / 500ms→4s 封顶）一字未动。**为什么必须改**：该用例钉的是"预算 800ms + 首次退避 500ms ⇒ 恰好 2 次尝试"，而两者只差 300ms，满载时会把门禁随机拦红。
    - **验证**：目标类 **4/4** 绿；`ai/agent` 全量 **156/156** 绿（例数口径未变，`F12` 的 156 仍准）；提交门禁 backend 74 + ai/agent 156 绿。口径不变 ⇒ spec 关键三行无需改动。
  - **实施记录与证据：见 `docs/plans/records/T18.md`**
- [x] T23（2026-09-15，用户拍板 **R1 = 接受**）**R1 收口
  - 验收：① 超时与取消各计一次且互不串味（真 registry 断言，非 mock）；② 不注入计数器时循环行为零变化；③ 指标在 `/actuator/metrics/<name>` 上可读；④ 门禁绿。
  - **未做 / 边界**：`MyManus` 旧端点**未接**该计数器（那是待处置的遗留端点，且不是本次要观测的链路）；**上游连接仍不会随取消关闭**——本次是"接受并让它可观测"，不是修掉它；接云端 provider 后若 `cancelled`/`timeout` 与"泄漏连接"的实际影响对不上，再谈换 connector。
  - **实施记录与证据：见 `docs/plans/records/T23.md`**
- [x] T19（2026-09-15 用户拍板"做 MCP 降级"）**搜图 MCP 不可达时的降级**：把"8127 没起 ⇒ 每次对话在装配工具那步整条抛、HTTP 200 + 业务码 50000（JSON、无 SSE 流）、前端只看到连接中断"改成"少一个工具照常开对话"，并且不让服务长时间不可用时每次对话都白等一次连接超时。
  - ① **失败即降级**：解析 MCP 工具回调失败（含 `getIfAvailable()` 阶段）⇒ `warn` 一条 + 本次会话不挂搜图工具，对话照常进行——沿用 T9 既有口径"少一个工具照常开对话"，做法与"没配视觉模型就不挂 visionTagger"同款。
  - ② **失败后冷却 60s（fail-fast）**：冷却期内不再尝试解析，直接按"本次不挂"处理；到期自动重试一次，成功即清除冷却。理由是服务可能长时间不可用，而每次失败要付 ≤20s 的连接超时。**参数 60s**（一个常量，与 T18 同款"有界失败窗口"口径）。
  - ③ **不动 `request-timeout`（默认 20s）**：这把尺同时约束真实的 `searchImage` 调用，压小会误伤正常路径 ⇒ 保留 20s，代价是"每个故障窗口第一次尝试仍要 ≤20s"（只付一次，冷却期内不再付）。
  - **口径不变 ⇒ spec 关键三行无需改动**：搜图仍是可选工具、MCP 仍可整体关掉（`AI_MCP_CLIENT_ENABLED=false`），本任务只补"服务不可达"这一形态的降级。
  - 不做：不做"显式健康探测后再挂"（plan 上一轮列的另一方向：它自带一把超时尺、且要在装配前多打一次网络往返，行为不如"失败即降级 + 冷却"可预测）；不做把降级原因推给前端/用户的独立事件（保持静默降级 + 日志，用户可见性记入已知限制）；不动 MyManus（它不挂 MCP 工具）；不改 `request-timeout`。
  - **未做 / 已知代价**：用户侧看不到"搜图工具本轮被回退"（模型照常作答；明确要求搜图时会得到工具错误）；降级粒度是整批 MCP 工具而非单个工具；冷却状态在进程内（多实例各自计时）。
  - **实施记录与证据：见 `docs/plans/records/T19.md`**
- [x] T20（2026-09-15，用户要求修复本轮 review）**T19 并发首探测与文档口径收口**：MCP 故障窗口内只允许一个会话执行工具列表探测，其余并发会话立即按“少一个工具”降级，避免多个请求依次支付约 20s 超时；同时订正 T19 的 HTTP/业务码与自愈时间表述。
  - 文件范围：`ai/agent/.../tools/mcp/McpToolCallbackResolver.java`、对应测试；`docs/plan.md`、`docs/features/F12-图库助手档3工具与联调.md`、最新 T19 handoff；不改 controller、MCP 配置、`request-timeout` 与用户可见事件。
  - 验收：先新增确定性并发复现测试并看到红（首个探测在途时，第二个会话必须快速返回且 provider 调用计数仍为 1）→ 实现 single-flight 后转绿；原 8 条 T19 测试保持绿；ai/agent 门禁绿。文档统一为“HTTP 200 + 业务码 50000”以及“失败被确认后最多再等 60s 重试（从服务恢复时刻计算还需叠加在途探测的剩余超时）”。
  - 口径不变：仍是 T19 的失败降级与 60s 冷却，只补并发正确性和文字精度，spec 关键三行无需修改。
  - **实施记录与证据：见 `docs/plans/records/T20.md`**
- [x] T21（2026-09-15，用户"执行"T8-hard review 遗留的三条 P3）**循环收尾与文档口径收口**
  - **用户可见影响**：回答气泡少一句多余的"执行结束：达到最大步骤 (N)"（仅当最终回答或卡死终止恰好落在第 `maxSteps` 步时出现；图库助手 `maxSteps=20`）。口径不变 ⇒ spec 关键三行无需改动。
  - **实施记录与证据：见 `docs/plans/records/T21.md`**
- [x] T22（2026-09-15，用户拍板"给用户可见提示"）**MCP 降级可见化 + 前端社交残留清理**：① 搜图 MCP 降级不再是静默的——引擎在本轮开头发一条 `notice` 帧，前端渲染成一行提示；② 删掉停用的帖子/社交前端残留，只保留通知。
  - 验收：① 降级时首帧是 `notice` 且只发一次；正常路径与"Bean 不存在（没启用 MCP）"**都不发**（后者是配置选择，不是故障）；② 前端 `type-check` 错误数与基线持平（124）、改动文件 eslint 0 错；③ `ai/agent` 164 例、backend 74 例绿。
  - **实施记录与证据：见 `docs/plans/records/T22.md`**
## 未决与非任务事项（不建任务）

> 2026-09-15 立此节，取代原来的「存量遗留（未做，未拍板）」——那节的内容（前端社交残留）已由 T22 收口，正文存 `docs/plans/records/section-01.md`。
> 本节只放**不属于任务**的东西：部署前置、待人工验收、以及按 AGENTS.md 规则 13 判为"已知限制并关闭"的项。**已关闭项不再挂账**；要做的时候才开任务。

### 已接受的暴露（用户判定，不再重提）

| # | 事实 | 判定 |
|---|---|---|
| A1 | **线上跑的仍是修复前的后端版本**：2026-09-15 匿名实测 `POST /api/space/list/page/vo` 回 `code:0` + 7 个空间完整元数据（含 `userId`/`userAccount`/`userName`/头像/空间规模）——正是 T3.2 要封的"匿名可枚举空间与属主"，而该集成测试断言为"匿名 40100"。即 T3.1/T3.2/T3.3 那批安全修复**已完成、已独立 review，但未部署**。信息暴露性质为**只读的信息泄露**（账号名与空间元数据；邮箱字段为 `null`），无写入、无账号接管 | **用户 2026-09-15 判定：站点无访问量，接受该暴露，不为此提前部署**。据此不再把它当紧急事项提出；将来部署后端时自然一并带上 |

### 部署前必办（2026-09-15 一次独立复核后升级优先级）

| # | 事项 | 为什么 | 状态 |
|---|---|---|---|
| ~~D1~~ | ~~引擎旧端点 `/ai/manus/chat` 必须加鉴权或只监听回环~~ | 它**零鉴权**（引擎只拦 `/ai/huoshan/**`），挂 `ToolRegistration.allTools()`；其中 `FileOperationTool` 的 `fileName` 未做路径清洗，配合"攻击者可控制 `message`=提示词"构成**未授权的任意文件读写**；另有抓取类工具的 SSRF 面 | **已收口（2026-09-15）**：引擎与 MCP 只监听回环（`server.address: 127.0.0.1`，在**入库的** `application.yaml` 里）+ `FileOperationTool` 补路径校验。口径见 `docs/decisions/2026-09-15-D1-loopback.md`；实施见 `docs/plans/records/D1.md` |
| D2 | 引擎 `CorsConfig` 收紧 | `allowCredentials(true)` + `allowedOriginPatterns("*")` 对所有站点反射 Origin 且允许带凭据 | **缓解未修**（D1 的回环绑定后远程浏览器够不到它；但**本机进程仍可利用**，不算已修好） |
| D3 | nginx SSE 配置入库 | `proxy_buffering off` / `proxy_read_timeout` 是折叠条逐步显示的**前提**；而 `backend/docs/deploy` 被 `backend/.gitignore:23` 排除、**不在版本控制内**（AGENTS.md 已注明） | **已产出（2026-09-15）**：`deploy/nginx/ai-assistant-sse.conf`（含超时取值理由 + 三步验法）。**未在真机验证**（我无服务器访问权） |
| D4 | 引擎与 MCP 的 prod 配置与环境变量 | 两份 prod yaml 均不入库（设计如此），需要一份可执行的部署清单。**注意 D1 已把"只监听回环"写进入库的 `application.yaml`，故该修复对 prod 自动生效**（prod 那份未覆盖 `server.address`） | **已产出（2026-09-15）**：`deploy/prod-checklist.md`。**并列了两个陷阱**：① 三个 jar 里都烤进了本机的 `application-local.yaml`，而后端默认 profile 就是 `local`（拿本机构建的 jar 直接起会连本机库、用本机密钥）；② 引擎的 `thinking: disabled` 不在入库配置里，prod 必须显式给（不给则 MiMo 进思考模式、`temperature` 失效） |
| D5 | 部署后勾 spec 的 F11 | 用户 2026-09-15 拍板"等一期部署上线后再勾" | 待部署 |

### 待人工验收（非任务；需要真实登录态/浏览器）

- ~~**T17 浏览器侧两种角色验收**~~、~~**T22 `notice` 行渲染**~~ —— **2026-09-15 两项都做完了，均通过**：管理员有 `visionTagger`、普通用户没有（UI + 引擎日志双判据），降级提示在回答上方正常渲染。完整过程、可复跑步骤、清理记录与三条顺带发现见 `docs/plans/records/acceptance-2026-09-15-T17-T22.md`。
- 仍可看：普通用户问打标时模型的话术（提示词未按角色补话术）——本次**已顺带看到**：普通用户那轮模型答"无法为具体图片提供打标建议"，措辞正常、没有暴露"你没有这个工具"这类内部信息。

### 数据卫生（需你点头才动）

- **历史探针数据没有清**：`yu_picture.user` 415 行里至少 20+ 个是前几轮会话的探针账号（`sec_t1_*`、`sec_t7_*`、`sec_e4*`、`zcodetest`、`66666`、`5555`、`1234`～`1234567` 等），`tag` 词表里也有 `T5新词*` 这类测试词（UI 上可见）。本次我建的账号已当场清除（DB + Redis 均已核实），但**删历史用户是破坏性操作，未动**。要清就开一个小任务，先列清单再删。

### 已判为「已知限制并关闭」（规则 13；不建任务、不再挂账）

- **R5 review 的 6 条 P3**（`chatId` 可换行进日志、relay 多行 `data:` 产出半帧、上游缺 `[DONE]` 时两句话同屏、上游非 JSON 错误体原文进气泡、线程池拒绝只给空体 500、代理 emitter 超时早于引擎）——文档见 `docs/features/F11-AI助手MVP.md`「已知限制」7–11。**其中两条在 2026-09-15 复核中被提升为 P2**（见下），其余关闭。
- **T8-hard review 的 P3**、**T6 review 的 P3**：均已收口或转入功能文档「已知限制」。
- **零碎**：`frontend/src/api/index.ts`（零引用死文件）、`typings.d.ts` 里的 `Post*` 类型——**关闭**：删除它们属于"顺手清理"，按规则 1 不主动做；真要清时随手开一个小任务即可。

### 复核提升为 P2（**三条已于 2026-09-15 收口**）

1. ~~**上游非 JSON 错误体原文进气泡**~~ → **已修**：非 JSON / 无 message 字段时改发通用文案，原文只进日志（`AiAssistantProxyManager.extractMessage`）。单测红→绿，`AiAssistantProxyManagerTest` 13/13（断言 `doesNotContain("Bad Gateway"/"nginx"/"<html>")`）。
2. ~~**前端取票不判 `data.code`**~~ → **已修**：改判 `code === 0` + 非空字符串，否则 throw 走既有文案。浏览器内故障注入验证：伪造 `HTTP 200 + code=50000` 后**一次 chat 都没发**，用户看到"未能取得本次对话凭据"而非"连接中断"。
3. ~~**回答里的 `## ` 标题字面显示**~~ → **已修**：新增 `heading` 块并渲染成真标题（`##`→h4、`###`→h5），浏览器实测无字面井号。

实施记录与全部证据：`docs/plans/records/assistant-visible-fixes.md`；口径：`docs/decisions/2026-09-15-assistant-visible-defects.md`。

配套：前端校验脚本**入库**（`frontend/scripts/check-assistant-format.mjs`，`npm run check:assistant-format`，21 断言），补上"复现测试永久保留"在无测试框架下的落点。

### 已判为「已知限制并关闭」补充（2026-09-15 验收新发现）

- ~~**步骤行里 `visionTagger` 没有中文别名与摘要**~~ → **已修**：与上面第 3 项同文件同函数，一并做了（字据里交代了重开理由）；同时补齐 `batchEditPictures` / `batchUploadByUrl` / `searchImage` 的别名与摘要。

## 任务清单（存量修复，已完成）

以下为 2026-09-12 功能审查（三个只读子代理 + 逐条人工核验，详见 handoff/0002）产出的修复批次，按优先级排列：

- [x] T1 工程化收尾：端口统一 8123、README 启动步骤修正、Redis 键前缀统一 `huoshantuku:` 并迁移存量数据（5c0ed24 / 5faf41f / fe71e78 / 596cb8f）
  - 文件范围：backend 配置与缓存相关类、README
  - 验收：已提交，CI 绿
- [x] T2 CI 门禁：GitHub Actions 跑编译 + 无 MySQL/Redis 单测（4f4cd5b）
  - 文件范围：.github/workflows/ci.yml
  - 验收：CI 绿
- [x] T3.1 图片读接口权限绑定目标资源，封堵"请求嗅探"越权
  - 文件范围：manager/auth/StpInterfaceImpl.java、config/HttpRequestWrapperFilter.java、controller/PictureController.java、service/impl/PictureServiceImpl.java、backend/src/test/（新增 HttpRequestWrapperFilterTest、PictureSpaceViewAuthIntegrationTest）
  - 验收：单测进门禁全绿；集成测试钉住"charset 变体令上下文为空 / spaceUserId 走私劫持 / 详情接口越权读"三条攻击路径全部 40101，属主与 viewer 成员的合法路径不受影响
- [x] T3.2 `/space/list/page/vo` 匿名枚举收紧（仅返回本人空间与已加入团队空间，admin 除外）；`/space/get/vo` 同步收紧为"与空间有归属关系才可查"
  - 文件范围：controller/SpaceController.java、service/impl/SpaceServiceImpl.java、service/ISpaceService.java
  - 验收：SpaceListAuthIntegrationTest 9 用例全绿（匿名 40100、无关用户不可见他人空间、viewer 可见已加入团队空间、属主/管理员正常）；已提交 c2f61e6
- [x] T3.3 会话生命周期：改密/删号/降权踢 Sa-Token 会话；getLoginUser 匿名请求不建会话
  - 文件范围：service/impl/UserServiceImpl.java、controller/UserController.java
  - 验收：UserSessionLifecycleIntegrationTest 4 用例全绿（resetPassword 后旧 satoken 失效且新密码可登录、删号踢会话、降权踢会话、匿名请求零会话）；已提交 5501642。已知残余：Spring Session（非 Sa-Token）在改密后仍有效，但因空间接口全部依赖 Sa-Token 登录态，残余影响限于个人资料编辑等自持操作，量级低
- [x] T3.4 重复上传额度重复累计修复（更新分支改为 `totalSize - oldSize + newSize`、count 不变）+ 旧 COS 对象清理
  - 文件范围：service/impl/PictureServiceImpl.java（uploadPicture 事务段）+ backend/src/test/（新增 PictureReplaceQuotaIntegrationTest）
  - 验收：PictureReplaceQuotaIntegrationTest 5 用例全绿（替换后额度净增量 = 新旧差值、缩图替换回落、超限替换整笔回滚且不清理旧文件、旧 URL 仍被其它记录引用时跳过清理、个人图库无空间也清理旧文件）；已提交 ad4dada
  - 实现备注：扣减用 `GREATEST(totalSize - oldSize, 0) + newSize`（与 deletePicture 同款防负数）；`cleanupPictureFile` 引用计数阈值由 `count > 1` 改为 `count > 0`（调用点查询时记录本身已删/已改指向，剩 1 条引用也不能删共享文件，原阈值会在恰好剩一条引用时误删）
- [x] T3.5 删空间级联（事务内逻辑删图片 + 异步清 COS + 包事务）、删用户级联
  - 文件范围：service/impl/SpaceServiceImpl.java、controller/UserController.java、service/ISpaceService.java（新增 deleteUserCascade 声明，接口变更超出原定范围已在此说明）+ backend/src/test/（新增 SpaceDeleteCascadeIntegrationTest）
  - 验收：SpaceDeleteCascadeIntegrationTest 2 用例全绿（删空间后图片逻辑删除、URL 仍被他人空间引用的图跳过清理、成员记录清理、他人空间不受影响；删号后名下空间级联、他人空间成员关系移除、其上传到他人空间的图保留）；全量 54 测试绿；已提交 8c702c1
  - 口径备注：删空间为单事务（空间行 + 成员 + 图片），COS 清理在事务提交后按 URL 引用计数异步执行；图片列表缓存不在级联中失效（空间删除后其缓存条目已不可达，TTL 兜底）；删号保留其上传到他人团队空间的图片（团队内容不随账号消失），如需一并删除另行立任务
- [x] T3.6 ShardingSphere 二选一 → 用户拍板第三选项：**注释停用（不删不启）**
  - 实际改动：application.yaml 分表死配置块注释停用（application-local/prod.yaml 属 gitignore 本地同步同步注释）；主类 exclude、pom 依赖、manager/sharding 两类保持原状作为停用护栏与恢复基础；README 本无分表表述，不改
  - 验收：全量 54 测试绿（上下文启动正常）；口径记入 decisions.md；已提交 d000360
- [x] T3.7 社交通知口径二选一 → 用户拍板：**保留通知接口，修正 spec/AGENTS 记录**
  - 实际改动：仅文档（docs/spec.md F7、AGENTS.md 技术栈与停用模块注记、decisions.md 两条口径）；前端核实 GlobalHeader.vue 正在调用 /notification/* 五接口，SocialController 未改动
  - 验收：spec F7 改为"部分停用：通知保留"；decisions.md 记入 T3.6/T3.7 两条口径
- [x] T3.8 空间额度预检误拒替换图片（T3.4 顺手发现的存量问题，用户 2026-09-13 点名）：预检移到 oldPicture 解析之后，仅对新增图片按新增口径预检；替换不增条数、大小按净差值在事务内原子校验，满员空间替换（缩图/等量）不再被"空间条数不足/大小不足"误拒
  - 验收：PictureReplacePrecheckOwnershipIntegrationTest——满员空间替换成功且额度记净差值（1000-500+200=700、条数不变）；满员空间新增仍被拒（守卫用例）；已提交 4d37b45
- [x] T3.9 admin 替换他人图片归属被改（T3.5 顺手发现的存量问题，用户 2026-09-13 点名）：getPicture 更新分支保留原归属 userId，仅新增图片归属上传人；管理员替换不再把图片改成自己的
  - 验收：admin 替换后 userId 不变、URL 更新；PictureReplacePrecheckOwnershipIntegrationTest 3 用例全绿；全量 57 测试绿；已提交 4d37b45
  - 独立 review（规则 13，2026-09-13）：**通过**（8 用例实测绿，静态推演确认回退即红）。P2 既有问题（非本次引入）已立 T3.10 收口。P3 已知限制：满员空间超额替换在 COS 上传后于事务内被拒、留孤儿对象（与新增路径既有失败面一致）；回归测试属 IntegrationTest 口径不入 CI/门禁（既有约定）
- [x] T3.10 替换图片 spaceId 反推路径缺空间上传权限校验（T3.8/T3.9 独立 review 发现的既有 P2，用户 2026-09-13 拍板收口）：uploadPicture 在 spaceId 由原图反推时补查目标空间并校验 PICTURE_UPLOAD（抽 checkSpaceUploadPermission 私有方法复用），与显式传 spaceId 路径、checkPictureAuth（deletePicture）判权同源
  - 口径后果（有意收紧）：站点管理员若非团队空间成员，替换/上传该空间图片将被拒（与显式传参路径及 deletePicture 现状一致）；私有空间属主/站点管理员不受影响，团队空间属主由 createSpace 自动建 admin 成员行、不受影响
  - 验收：PictureReplaceSpaceAuthIntegrationTest 4 用例全绿（被移出成员替换被拒、降权 viewer 替换被拒、非成员站点管理员替换被拒、editor 成员正常替换且归属不变）；全量 61 测试绿；已提交 8b29f46
- [x] T3.11 上传事务失败后补偿删除刚上传的 COS 文件（P3 孤儿对象收口，用户 2026-09-13 点名选项①）：transactionTemplate.execute 包 try-catch，事务回滚后按引用计数（count == 0 才删）异步清理刚上传的新文件；原异常照常抛出。覆盖替换超额与"过预检但事务内原子校验失败"的新增两条失败路径
  - 口径：COS 上传在事务前这一时序不变（picSize 只有上传后才知道）；cleanupPictureFile javadoc 补"事务回滚后清理孤儿文件"第三类调用前提
  - 验收：PictureReplaceQuotaIntegrationTest 6 用例全绿（超额替换：旧文件绝不动 + 新文件被补偿清理；新增超额：补偿清理；另 4 用例回归不变）；全量 62 测试绿；已提交 5f44ecc
