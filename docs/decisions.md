# Decisions — 火山图库（huoshan-picture）

<!-- freshness: { owner: "lcl", reviewed: "2026-09-28" } -->

> 已确认的决定记录在案，AI 不得推翻；翻案必须先经用户同意并追加记录。
> **本文件是"生效口径表"，也是唯一权威清单**；"决定"列写一句话（≤160 字）+ 状态 + 详情指针。**详情列短则内联、长则链接**，两者冲突时以本表为准。
> **详情不可变**：`docs/decisions/` 下的文件写定后不再修改（勘误除外）——状态变化只改本表"状态"列；决定被推翻时新增一行并注明"取代 <标识>"，旧行状态改"已废弃"，不删历史。
> **状态词表**：`生效` / `生效（有条件）` / `已收口` / `已废弃`。禁止"挂账"——要么现在修，要么判为已知限制并关闭（AGENTS.md 规则 13）。
> 接入前的存量选型（单仓库 / SB 2.7 / ShardingSphere / Sa-Token 等 10 条）见 `docs/decisions/legacy-stack.md`。

| 日期 | 决定 | 详情 | 状态 |
|---|---|---|---|
| 2026-09-12 | 定级：认真轨 | 存量项目已上线运营，用户主动要求接入 vibe 工作流，仓库已有 CI 与测试 | 生效 |
| 2026-09-12 | 帖子/社交模块（Post/PostInteraction/Social/UserFollow）停用：类注解已注释，不被 Spring 加载 | 用户 2026-09-12 确认 | 生效 |
| 2026-09-12 | 接入 vibe 工作流：AGENTS.md + docs/ 档案 + .githooks pre-commit 测试门禁（门禁复用 CI 同口径单测命令） | 用户要求"接入工作流" | 生效 |
| 2026-09-13 | 对象级鉴权口径：读接口必须绑定目标资源（服务端查出归属再判权，如 checkPictureAuth / spaceUserAuthManager.getPermissionList），禁止依赖 StpInterfaceImpl 从请求嗅探的上下文做空间 scoped 读授权；嗅探层仅作登录/公共图库兜底 | T3.1 修复落定的原则：请求参数可定位资源但不可定权 | 生效 |
| 2026-09-13 | 会话生命周期口径：改密/删号/角色变更必须 StpKit.SPACE.logout(userId) 踢会话；getLoginUser 用 getSession(false)，匿名请求不创建会话 | T3.3：旧会话快照（含 admin 角色）最长 7 天内仍参与空间鉴权 | 生效 |
| 2026-09-13 | 接受残余：Spring Session 在改密后不失效——空间接口全部依赖 Sa-Token 登录态，残余影响限于个人资料编辑等自持操作；如需彻底收敛再做会话版本号机制 | T3.3 收权后评估，量级低 | 生效 |
| 2026-09-13 | T3.6 口径：ShardingSphere 不删不启——注释停用预留，恢复分表前须先解决三个坑（库名不一致 / 空 range 静默丢数据 / 动态建表回退主表） | [理由与证据](decisions/2026-09-13-T3.6.md) | 生效 |
| 2026-09-13 | T3.7 口径修订：社交模块通知接口保留——SocialController 的 /notification/* 在服务（前端 GlobalHeader 铃铛正在调用），仅 /timeline 停用；修订 2026-09-12"Social 不被 Spring 加载"的不实记录 | 用户拍板"保留通知并改 spec" | 生效 |
| 2026-09-13 | 维持"CI/门禁排除集成测试"现状：集成测试不入 CI，靠本地全量手动回归（大改动后/发版前必跑）；不采纳"CI 加 MySQL/Redis service 容器跑全量"与"nightly 定时全量" | T3.8-T3.11 独立 review 提出 P3（本批修复的回归锁全在集成测试），用户在三个收口选项中拍板"不动" | 生效 |
| 2026-09-13 | **AI 助手一期立项**：内置助手（前端界面 + 后端代理 + 外部 MyManus 引擎 headless）、一期场景 = 智能整理 + 选图入库、配套 `tag` 全局词表；一期不做删除类工具 / AI 审核助手 / 词表空间隔离 | [理由与证据](decisions/2026-09-13-ai-kickoff.md) | 生效 |
| 2026-09-13 | **R2：MyManus 引擎与 image-search-mcp-server 整体迁入本仓库**（`ai/` 独立 Maven 应用、独立进程）；后续开发集中本仓库；源仓库 yu-ai-agent 冻结归档（挂账 T1 移交本仓 T13、T2 作废） | [理由与证据](decisions/2026-09-13-R2-monorepo.md) | 生效 |
| 2026-09-14 | T5 词表落地口径：列名随库内 camelCase 记 `usageCount`；语义 = 一次编辑调用 +1、只加不减；词表无管理端点/无删除入口（逻辑删除后同名词条不可再注册）；`/picture/update` 不接词表 | T5 实施定稿（docs/features/F10-标签词表.md 已知限制、records/T5.md） | 生效 |
| 2026-09-14 | T6 模型接入口径：两模型装配为 `OpenAiChatModels` 持有器、**不注册为 ChatModel Bean**；`application.yaml` 显式声明其余模态 `none`；base-url 填协议根、不带尾部 `/v1` | [理由与证据](decisions/2026-09-14-T6.md) | 生效 |
| 2026-09-14 | T6 独立 review：**有条件通过** | 交付本体经字节码反查与实测复核成立（守卫恰好覆盖 OpenAI 六条 `spring.ai.model.*` 门、base-url/半配置语义实测与文档一致、装配进真实上下文不扰默认链路），但**流式路径无任何超时**（`webClientBuilder` 未传，实测 `JdkClientHttpConnector.readTimeout=null`，配置 3s 时流式 25s 仍不返回），已立 T6.1（P1，T8 前必补）；另有"超时已生效"无测试守护（P2）与守卫测试看不到 profile 覆盖、timeout 裸数字=毫秒、base-url 单独配置被静默忽略等 P3（见 plan.md T6 review 记录） | 生效 |
| 2026-09-14 | T6.1 流式超时口径：WebClient 显式接 JDK connector（连接/读超时）；流空闲超时用 `ExchangeFilterFunction` 逐请求计时（`body.timeout`），**不用 ChatModel 装饰器**；复用 `app.ai.openai.*.timeout` 一把尺（0 = 不限制） | [理由与证据](decisions/2026-09-14-T6.1-impl.md) | 生效 |
| 2026-09-14 | **档 1 端点鉴权定为 A：服务间 API key**（用户拍板）：图库助手 headless 端点只认 `X-Internal-Api-Key`，`app.huoshan.headless-api-key` 为空时**一律拒绝**（fail-closed），比对用 `MessageDigest.isEqual`；用… | [理由与证据](decisions/2026-09-14-stage1.md) | 生效 |
| 2026-09-14 | 旧端点 `/ai/manus/chat` 档 1 不锁（理由与演变见详情）；**2026-09-15 由 D1 口径收口**：只监听回环、不加鉴权 | [理由与证据](decisions/2026-09-14-manus-endpoint.md) | 已收口 |
| 2026-09-14 | 超时转显式错误事件 + 超时/取消埋点**顺延到档 2**（与前端错误渲染同批验收） | 它要改 `BaseAgent` 共享循环（会影响旧端点），且其价值需前端可见的错误态才能端到端验收；属对 plan.md T8 行"T8 只做超时转显式错误事件"的档位再切分（口径见档 1 计划文档"实现口径说明 6"） | 生效 |
| 2026-09-14 | **待决策：空间列表接口的登录态不吃 sa-token**（档 1 冒烟发现，影响档 2 的 T10）… | [理由与证据](decisions/2026-09-14-topic02.md) | **生效（B）** |
| 2026-09-14 | **凭据组口径（B 落地）**：代理透传一组凭据（satoken + Spring Session 会话 Cookie）；两把都只在请求生命周期内存流转、不落库、不进日志、不入记忆；缺任一把在端点入口即拒（40000） | [理由与证据](decisions/2026-09-14-credential-group.md) | 生效 |
| 2026-09-14 | 工具返回与入参的 **id 一律按字符串透传**：`SpaceItem.id` / `PictureItem.id` / `PictureItem.spaceId` 以及 `listPictures` 的 `spaceId` 入参都是 String，并在工具描述与系统提示词里明示"id 是字符串、必须原样复制" | 图库把 Long 统一序列化成字符串（`JsonConfig` 的 `ToStringSerializer`）正是为防 JS 精度丢失；档 1 冒烟实测工具把它转回数字后，模型读 `2099389400592543745`、写回 `2099389400592543700`（末两位被 IEEE754 双精度截断），导致 `listPictures` 报 `40400 空间不存在` | 生效 |
| 2026-09-14 | T6.1 独立 review（规则 13，只读会话）：**有条件通过**——两条机制本体成立（本会话自建探针独立复现"缺一不可"：只挂 connector + 吐两段后静默 → 6s 无信号；完整装配 + 从不响应 → 1020ms 抛 `WebClientRequestException <- HttpTimeout… | [理由与证据](decisions/2026-09-14-T6.1.md) | 生效 |
| 2026-09-14 | 转向"MVP 优先"（用户拍板"先做出来能用，再慢慢优化"）：一期范围不变，改为**档位推进**——档 1 headless 打通（T8 的会话类型 / headless SSE 端点 / 服务间 API key / `huoshan:<userId>` 身份映射，**循环不重构**；T7 的 `listSpaces`… | [理由与证据](decisions/2026-09-14-topic04.md) | 生效 |
| 2026-09-14 | **档 2 代理实现口径（T10）**：SSE 转发用 `SseEmitter` + JDK `HttpURLConnection` + 专用有界线程池；代理只判凭据存在性、不判身份；上游错误合成一条 `answer` + `[DONE]`；客户端断流即 disconnect 上游；两个开关任一为空即拒（fail-closed） | [理由与证据](decisions/2026-09-14-T10.md) | 生效 |
| 2026-09-14 | **会话 Cookie 必须原样透传，禁止用 `session.getId()`**：代理按 Cookie 名（取自 `server.servlet.session.cookie.name`，默认 `SESSION`）取浏览器原值转发；`satoken` 按 头 → Cookie → query 三处取 | 首轮真图库冒烟工具回 `40100 未登录`：Spring Session 默认把会话 id 做 Base64 写进 Cookie，`getId()` 返回解码后的 id，两者不等价。实测对照（同一探针会话）：原样 Cookie 值打 `/space/list/page/vo` 回 `code=0`（读到真实空间），解码值回 `40100`。单测与集成测试各留一条回归钉住 | 生效 |
| 2026-09-14 | **前端助手客户端口径（T11）**：原生 `EventSource`（GET + withCredentials）；`[DONE]` 与 `onerror` 都必须显式 `close()`；未登录进页面前重定向；对话历史不落库（`chatId` 存 sessionStorage） | [理由与证据](decisions/2026-09-14-T11-client.md) | 生效 |
| 2026-09-14 | **记录下来：前端门禁存量红**（口径与处置） | `npm run type-check` 138 例、`eslint .` 73 例既有错误，全在本次未触碰的文件里（多为 `LocationQueryValue` 类型不兼容、`any`、未用变量一类，疑似 vue-tsc/eslint 版本升级后的存量债）；T11 改动净增 0（改动前后均 138）。AGENTS 与 handoff 0001 记的"前端以 type-check + lint 为准"**目前不成立**，需单独立任务清理；按规则 1 本次不顺手改 | 生效（待清理） |
| 2026-09-14 | **助手呈现层口径（T11.1）**：回答渲染 Markdown 子集（自写解析 + Vue 模板插值，**不引库、不用 `v-html`**）；工具步骤默认只显示「中文别名 + 一句话摘要」，原始 JSON 收进「详情」默认收起；解析失败退化为"已完成" | [理由与证据](decisions/2026-09-14-T11.1.md) | 生效 |
| 2026-09-14 | **R4/R6 收口（用户拍板"R4 同意加校验、R6 做"）**：**R4** 代理入口新增**身份一致性校验**——`StpKit.SPACE.getLoginIdByToken(satoken)` 必须非空且与 Spring Session 登录用户同 id，否则直接拒绝、零上游请求：**无效/过期 → 4010… | [理由与证据](decisions/2026-09-14-R4.md) | 生效 |
| 2026-09-14 | **档 2 里程碑独立 review（规则 13，只读会话）：有条件通过**… | [理由与证据](decisions/2026-09-14-stage2.md) | 生效（有条件通过） |
| 2026-09-14 | **R5 收口口径（用户拍板"采用一次性 ticket"）**：`GET /ai/assistant/chat` 新增**必需参数 `ticket`**，由新增的 `POST /ai/assistant/ticket` 签发（走与 chat 同一套登录门槛）；票存 Redis（键 `huoshantuku:ai:ass… | [理由与证据](decisions/2026-09-14-R5.md) | 生效 |
| 2026-09-14 | **本机 Redis 版本约束：新增 Redis 用法不得假定 6.2+ 命令**（`GETDEL` 等一律不能用）；需要"取用即删"这类原子语义时用 **Lua 脚本**（GET+DEL），与 `PictureServiceImpl.UNLOCK_SCRIPT` 同口径 | R5 的首版实现用了 `ValueOperations.getAndDelete`（下发 `GETDEL`）：**门禁单测 20/20 全绿，而集成测试直接报 `ERR unknown command 'GETDEL'`**——替身测不到真实 Redis 的命令可用性，这正是"要连真环境的集成测试"的价值所在；本机实测 `redis_version:5.0.14.1`，且部署环境的 Redis 版本不受本仓代码控制，故按 5.x 兼容写 | 生效 |
| 2026-09-14 | **R5 收口独立 review（规则 13，只读会话）：有条件通过——R5 本体成立、无 P1；1 条 P2 + 6 条 P3 全为加固项，不阻塞本轮 accept**… | [理由与证据](decisions/2026-09-14-R5-2.md) | 生效（有条件通过） |
| 2026-09-14 | **生产 CORS 白名单移除 apex 域名（用户拍板；即上条 review 的 P3(h)/(⑪) 收口，代码提交 `1c732db`）**：`application.yaml:100`（**跟踪文件**）的 `app.cors.allowed-origins` 去掉 `https://lincode.online… | [理由与证据](decisions/2026-09-14-topic09.md) | 生效 |
| 2026-09-14 | **WebSocket 握手来源白名单改为共用 `app.cors.allowed-origins`（承接上一条的"同类未改"项，用户拍板"一起做"，代码提交 `2b29786`）**：`WebSocketConfig` 不再手写 `("http://localhost:*", "https://www.lincode… | [理由与证据](decisions/2026-09-14-topic10.md) | 生效 |
| 2026-09-14 | **R5 review 的 P2①（验收 ⑤ 口径）按用户拍板"补跑探针"收口；R5 review 结论由此转为"通过"**：跨站导航探针已实跑（用**按当前 HEAD 重新打包**的 jar——当日 19:43 那个旧 jar 早于 R5 代码 `af2995f`，拿它复跑会得到相反结论）… | [理由与证据](decisions/2026-09-14-R5-3.md) | 生效 |
| 2026-09-14 | **档 3 T7 工具集口径（写类工具 + 看图打标）**：① **批量编辑的 id 按字符串进 body**（与既有 `listPictures` 的 `spaceId` 同口径），靠图库侧 Jackson 把纯数字字符串强转成 `Long`——位数原样保留，不赌"数字进 JSON 也不丢精度"；② **批量编辑的返… | [理由与证据](decisions/2026-09-14-stage3.md) | 生效 |
| 2026-09-14 | **T9 搜图 MCP 接入口径**：① **传输方式定 SSE**（MCP 服务 `ai/image-search-mcp-server` 默认 profile 就是 `sse`、端口 8127；stdio 需要引擎在本机 fork 一个 java 进程，部署形态更脆）；② **`spring.ai.mcp.clie… | [理由与证据](decisions/2026-09-14-T9.md) | 生效 |
| 2026-09-14 | **图库助手模型接入 DeepSeek 官方（`deepseek-flash`，用户指定）**：① **base-url = `https://api.deepseek.com`**（协议根、不带尾部 `/v1`，与 T6 口径一致——Spring AI 自己拼 `/v1/chat/completions`）；② **… | [理由与证据](decisions/2026-09-14-topic14.md) | 生效 |
| 2026-09-14 | **T12 联调发现并修复：记忆回放必须剥掉"未配对的工具调用痕迹"（否则 DeepSeek 严格校验下第二轮工具调用必 400）**：现象——真机跑"帮我整理空间里的图"，第一次 `listSpaces` 成功，紧接着**第二次模型调用直接 400**：`An assistant message with 'tool… | [理由与证据](decisions/2026-09-14-T12.md) | 生效 |
| 2026-09-14 | 工具入参剥离包裹引号（T12 实测）：`cleanList` 增加成对引号拆除（`"`/`'`/反引号，可套多层）；不按逗号拆分元素（URL 逗号合法，启发式拆分风险大于收益，记为脆弱点） | [理由与证据](decisions/2026-09-14-T12-2.md) | 生效 |
| 2026-09-15 | **会话记忆按指纹去重（修"同一条用户消息被反复入库"）**：现象——`chat_message` 里同一条用户消息有 **3 份**… | [理由与证据](decisions/2026-09-15-topic01.md) | 生效 |
| 2026-09-15 | **图库助手模型由 DeepSeek 换成小米 MiMo（`mimo-v2.5`，用户指定）**：① base-url = `https://api.xiaomimimo.com`（协议根；MiMo 文档给的请求地址是 `https://api.xiaomimimo.com/v1/chat/completions`，S… | [理由与证据](decisions/2026-09-15-topic02.md) | 已废弃（2026-09-28 型号升级） |
| 2026-09-15 | **T8-hard 循环收敛（用户拍板：T8-c 纳入本轮、`MyManusTest` 直接删、`runSummary()` 保留）**：① `BaseAgent.run()` 退役，`runStream()` 成为唯一运行入口，两套循环收敛到 `runLoop(String, AgentEventListener)`… | [理由与证据](decisions/2026-09-15-T8.md) | 生效 |
| 2026-09-15 | **T8-c：失败与超时改发显式 `error` 事件，原始异常不再进事件流（规则 12 先红后绿）**：① 新增 `AgentEvent.Error` 与监听器 `event=error` 帧；② `ToolCallAgent.think()` 与 `ReActAgent.step()` **不再吞异常**——原 `… | [理由与证据](decisions/2026-09-15-T8-2.md) | 生效 |
| 2026-09-15 | **T8-hard 独立 review：有条件通过**（P2 已由 T14 收口、P3 已由 T21 收口；完整处置与观察见详情） | [理由与证据](decisions/2026-09-15-T8-3.md) | 生效 |
| 2026-09-15 | **T13 顺带项收口：删除两个"不注册的教学遗留工具"（用户拍板"两个都删"，提交 `fdbcab7`）**：`TerminalOperationTool`（73 行，`ProcessBuilder("cmd.exe","/c", <模型给的命令>)`、工作目录是用户 home）与 `ParallelSearchTo… | [理由与证据](decisions/2026-09-15-T13.md) | 生效 |
| 2026-09-15 | **AI 打标立项与落地口径（T15；用户拍板：只管理员手动触发、两步式、普通用户助手摘掉只读打标）**：① 打标能力放**图库 backend**——新增 `api/vision/AiVisionTagApi`（OpenAI 兼容看图）+ `manager/ai/PictureAiTagManager`（提示词 / … | [理由与证据](decisions/2026-09-15-T15.md) | 生效 |
| 2026-09-15 | **T18 LLM 调用的失败时限收口（用户拍板参数：重试预算 45s、尝试 ≤4 次、退避 500ms→4s；看图按 T15 的"层次一"对齐）**：① `OpenAiChatModels` 新增 `boundedRetryTemplate()` 并接到主脑与看图两个模型（`retryTemplate(...)`）；… | [理由与证据](decisions/2026-09-15-T18.md) | 生效 |
| 2026-09-15 | **R1（取消不关闭上游请求）事实订正：当前不可达（潜在缺陷，不是在发生的泄漏）**——R1 的机制是 Spring `JdkClientHttpResponse.adaptBody` 用 `Flux.cache(0)`（`replay(0).autoConnect()`）包响应体，导致下游取消/超时**不断开上游**… | [理由与证据](decisions/2026-09-15-R1.md) | 生效（事实订正，无行为改动） |
| 2026-09-15 | 实测发现（T18 真机）：MCP 服务不可达时每次对话整条失败（20s 后 50000、无 SSE）→ **已由 T19 失败降级 + T22 可见化收口** | [原文与收口](decisions/2026-09-15-mcp-unreachable.md) | 已收口 |
| 2026-09-15 | **前端 API 客户端不跑生成器：新接口一律按现有风格手写进 `api/*.ts`（T16 实测）**：执行 `npm run openapi`（`@umijs/openapi` 指向 `http://localhost:8123/api/v2/api-docs`）会把 `frontend/src/api/` **全… | [理由与证据](decisions/2026-09-15-topic10.md) | 生效 |
| 2026-09-15 | 实测发现（T8-d 真机）：provider 不可达族的失败落在 error 事件之外（重试总时长超 emitter 300s）→ **已由 T18 重试预算封顶（45s / ≤4 次）收口** | [原文与收口](decisions/2026-09-15-provider-unreachable.md) | 已收口 |
| 2026-09-15 | **T17 助手侧按角色裁剪看图打标工具（用户 2026-09-15 拍板"普通用户助手里的只读 visionTagger 摘掉"）**：① 新增**调用者角色信道**——backend 代理把 `loginUser.getUserRole()`（空值按 `UserConstant.DEFAULT_ROLE`）放进 `… | [理由与证据](decisions/2026-09-15-T17.md) | 生效 |
| 2026-09-15 | **T19 搜图 MCP 不可达时的降级（用户 2026-09-15 拍板"做 MCP 降级"）**：① 解析 MCP 工具回调失败（含"取 Bean"与"列工具"两步）⇒ **降级为空数组** + 一条 warn，对话照常进行（沿用 T9"少一个工具照常开对话"口径）；② 失败后**进入 60s 冷却**——冷却期内… | [理由与证据](decisions/2026-09-15-T19.md) | 生效 |
| 2026-09-15 | **T23：R1（取消/超时不关闭上游连接）拍板"接受" + 落地计数埋点（用户拍板）**：把 2026-09-14 的"延后到档 2 之后"推进为**明确接受**该行为，并补上当时就要求的观测手段——`AgentRunMetrics` 注册两个 Micrometer 计数器 `huoshan.agent.run.ti… | [理由与证据](decisions/2026-09-15-T23.md) | 生效 |
| 2026-09-15 | **T24：AI 打标"现场验收"完成（用户浏览器实测：出建议 → 确认入库可用）+ 真库核对佐证 + 两条负向证据**… | [理由与证据](decisions/2026-09-15-T24.md) | **口径补充（下次别踩）**：真机验收要用 `--spring.profiles.active=local,test` 起后端——test profile 关掉图形/邮箱验证码校验，登录只需账号+密码（见 `application-test.yaml` 头注释）。**观察（未定性，留给下次）**：AI 用"手搓 HTTP"（SESSION+satoken 双凭据都已带上）打 `ai_tag/suggest` 仍被 `40400` 拒，而同一套凭据打 `/user/get/login` 正常返回 `userRole=admin`、浏览器里同一账号点同一入口可用 ⇒ **差异在我的请求构造，不在数据或应用逻辑**；要复现请从浏览器 DevTools 取 "Copy as cURL" 原样回放（比继续猜请求头快）。**打标验收记录尚未镜像进 `plan.md` T15 条目**（本轮上下文用尽，仅此一处补记待办）。 |
| 2026-09-15 | **T22：MCP 降级改为用户可见提示 + 前端社交残留清理 + F11 勾选时机确定（用户拍板）**：① **反转 T19 的"静默降级"决定**——降级时在本轮开头发一条 `notice` 帧（文案"图片搜索服务暂时不可用，本轮对话无法帮你搜图（其它能力不受影响）"），前端渲染成回答上方一行弱化文本；② 前端**… | [理由与证据](decisions/2026-09-15-T22.md) | 生效 |
| 2026-09-15 | **T21 收口 T8-hard review 遗留的三条 P3**：① `BaseAgent.runLoop` 尾部的上限提示加前置条件 `state == RUNNING`——**用户可见的行为修正**（回答气泡不再多一句"执行结束：达到最大步骤 (N)"）；② `AgentEvent.Error` javadoc… | [理由与证据](decisions/2026-09-15-T21.md) | 生效 |
| 2026-09-15 | **流程损耗治理（用户 2026-09-15 拍板"按你的建议来做"）**：新增「文档归属（单一事实源）」；字据收窄到四类；review 输出只剩"现在修 / 判为已知限制并关闭"两类、取消挂账；收口记录并入 handoff、handoff 一天一份只写增量；证据分"可复跑 / 不可复跑"；新增文档一致性门禁 | [理由与证据](decisions/2026-09-15-process-overhead.md) | 生效 |
| 2026-09-15 | AGENTS.md 事实订正：`backend/docs/deploy` 不在版本控制内（被 backend/.gitignore 排除）；前端门禁口径改为"改动不得净增"并配基线文件 `frontend/type-check-baseline.txt` | [理由与证据](decisions/2026-09-15-process-overhead.md) | 生效 |
| 2026-09-15 | **A1 已接受暴露（用户判定）**：线上曾跑修复前后端、`/space` 匿名可枚举——用户判定"站点无访问量、接受、不提前部署"（只读信息泄露）；2026-09-17 随新后端部署自然收口 | 实测原文与判定见 `docs/plans/archive/2026-09-未决与关闭事项.md`（A1 节） | 生效 |
| 2026-09-15 | **spec「关键三行」缩短（用户答复"落地"，规则 3 第②类）**：三行只留合同（目标 / 不做的事 / 边界），立项史与进度流水账移出；F11 改为指向 features 与任务记录；spec.md 10329 B → 5680 B | [理由与证据](decisions/2026-09-15-spec-key-three-lines.md) | 生效 |
| 2026-09-15 | **D1 收口（用户批准）**：引擎与 MCP 只监听回环 + `FileOperationTool` 路径校验；**不给 `/ai/manus/**` 加鉴权**（服务冻结前端，加 key 会打死它；部署拓扑不需要它对外可达）；D2 与抓取类 SSRF = 缓解未修 | [理由与证据](decisions/2026-09-15-D1-loopback.md) | 生效 |
| 2026-09-15 | **助手三处用户可见缺陷收口**：① 上游非 JSON 错误体不再进气泡（原文转日志）；② 前端取票判 `data.code`；③ `## 标题` 渲染为真标题（不产出 HTML，XSS 面不变）；另重开同日关闭的「visionTagger 无中文别名与摘要」一并修 | [理由与证据](decisions/2026-09-15-assistant-visible-defects.md) | 生效 |
| 2026-09-16 | **引擎摘掉 DashScope（用户指示）**：三处调用点（会话摘要、`/ai/manus/chat`、会话标题）全改走助手主脑，主脑成为唯一模型提供方、不做跨厂商回退；入库配置显式关整个集成（`model.chat: none` 管不到该自动配置）；prod 不再需要 `AI_DASHSCOPE_API_KEY` | [理由与证据](decisions/2026-09-16-dashscope-decoupling.md) | 生效 |
| 2026-09-16 | **引擎移除 Flyway（用户指示）**：迁移脚本 / 依赖 / `spring.flyway` 配置一并删除；库结构改由入库手工建表脚本 `deploy/sql/engine-schema.sql` 建立（V1 是 MariaDB 语法、空库首跑本就坏；V2–V4 的表零引用） | [理由与证据](decisions/2026-09-16-flyway-removed.md) | 生效 |
| 2026-09-16 | **AI 助手后端安全 review：有条件通过，需现在修**——两项 P1（代理自动重定向泄露凭据、写工具无确定性确认门）与六项 P2（query satoken、SSE 早取消竞态、INFO 原文日志、异常原文外泄、错误体无界读取、凭据名硬编码）统一纳入 T27；F11 已关闭的 SSE/超时/频控等限制不重复挂账 | [结论与修复计划](decisions/2026-09-16-assistant-backend-security-review.md) | 生效 |
| 2026-09-16 | **首次公开部署前暂时关闭助手直接写入（用户确认“第一阶段前 4 点”）**：先完成凭据/重定向与日志脱敏；确定性确认门完成前，从助手工具装配中摘掉 `batchEditPictures` / `batchUploadByUrl`，页面同步为查询、搜图候选和建议能力；普通图库编辑/上传与管理端 AI 打标不受影响 | [理由与边界](decisions/2026-09-16-first-release-readonly-assistant.md) | 生效 |
| 2026-09-16 | **引擎 MySQL 数据库命名统一为 `huoshan_ai_agent`**：替代迁入前遗留的 `yu-ai-agent`；首次生产部署直接创建新名，本机旧开发库不自动删除 | [理由与边界](decisions/2026-09-16-engine-database-name.md) | 生效 |
| 2026-09-17 | **压测资产单一事实源定为 `backend/jmeter/`，A/B 一律分进程（独立 JVM）**；v3 的同进程并发口径作废（§5.1 p95 降级为定性证据）；三份 JMeter 计划断言一律断响应体 `"code":0` 而非 HTTP 状态码 | [理由与证据](decisions/2026-09-17-jmeter-source-of-truth.md)（用法见 `backend/jmeter/README.md`） | 生效 |
| 2026-09-18 | 缓存 vs DB A/B 实跑完成（两轮，以第二轮为准）：约 67 万样本、错误率 0%，100 档吞吐比 1.78×（703.2 vs 395.0 QPS）；**基线选取口径缺陷待用户拍板**，`compare-ab.py` 未改 | [数字明细](decisions/2026-09-18-ab-first-run.md)：另见 `backend/jmeter/README.md` §5.6、`docs/plans/records/ab-first-run-2026-09-18.md` | 生效 |
| 2026-09-18 | **文档保养首轮（用户拍板）**：结转归档、decisions 压缩（一行 ≤160 字 + 详情不可变）、handoff 保留 8 份、spec 三行净化；新增文档寿命与保养规则与门禁预算/freshness 检查 | 清单与执行见 docs/workflow-history.md 2026-09-18 节 | 生效 |
| 2026-09-18 | **批量抓取落库批量化（用户批准）**：并行线程只做下载 + COS，`allOf` 后一次 `saveBatch` + 一次清缓存；**入库阶段整批原子**（失败整批回滚 + 补偿删已转存文件），下载/COS 失败仍逐张跳过；源地址批内去重；prod 的 JDBC 暂不加 `rewriteBatchedStatements` | [理由与代价](decisions/2026-09-18-batch-crawl-persist.md) | 生效 |
| 2026-09-18 | **URL 导入判重口径（用户批准：源地址列 + 按空间）**：新增 `picture.sourceUrl`，判重 = 源地址 + 目标空间（跨空间与公共图库互不影响、逻辑删除后可重导）；命中仍拒；不建唯一索引；删掉永不命中的 `getByUrl`；DDL 必须先于新 jar 手工执行 | [理由与未选方案](decisions/2026-09-18-source-url-dedupe.md) | 生效 |
| 2026-09-18 | **T28/T29/T30 独立 review：有条件通过**：① `sourceUrl` 超长会在整批原子下放大成整批 500，已修（T31）；② 0 张路径真机冒烟交用户执行。另 4 项判为已知限制并关闭、prod JDBC 开关维持既有决定 | [报告要点与未验证项](decisions/2026-09-18-review-batch-crawl.md) | 生效 |
| 2026-09-27 | **记忆读写去 advisor 化（T32，用户提出）**：移除 `MessageChatMemoryAdvisor`（每轮重读全量注入 + 回写最后一条用户消息），改为循环显式读写——运行开始读一次压缩视图作前缀、写一次用户消息，每步写一次助手消息；指纹去重随之退役；压缩预算 4096→32768（主脑 1M 上下文） | [理由与影响](decisions/2026-09-27-memory-explicit-rw.md) | 生效 |
| 2026-09-27 | **循环检测重写为双信号滑窗（T33）**：废弃 OpenManus 式"assistant 文本全局计数"（空文本决策行致盲、合法重复误报），改为循环签名（工具签名优先）+ 最近 4 步滑窗 ≥2 判卡死；纠偏 1 次后即强停；RUNNING 才检测（修双 answer） | [理由与调研](decisions/2026-09-27-loop-detection-dual-signal.md) | 生效 |
| 2026-09-28 | **助手模型升级为 `mimo-v2.6-flash`（T35，用户指定）**：主脑与 vision 两组同换——官方"图片理解"支持列表含该型号、输入模态含图像，旧口径"图像输入仅 v2.5 支持"已失效；base-url 与关思考模式口径不变，零代码改动；线上 prod 型号另由服务器环境变量决定 | [理由与待验证](decisions/2026-09-28-model-upgrade.md) | 生效 |
| 2026-09-28 | **助手快赢包口径（T36–T39，字据已确认）**：会话索引落 backend 新表 `ai_conversation`（建流前 upsert，标题=首条消息截断），引擎只读历史端点 + 代理逐字透传；失败帧 answer→error；会话不做重命名/删除 | [设计](designs/2026-09-28-助手快赢包设计.md)、[记录](plans/records/T36-T39-助手快赢包.md) | 生效 |
| 2026-09-29 | **助手写操作确认门协议（T27.4，字据已放行）**：写类工具改"准备→确认→执行"——模型只提交操作计划（准备零写库），backend 持有计划并单次消费凭据（绑定用户/会话/摘要、短 TTL），执行内容与确认卡片逐字段一致；模型自然语言判断不构成授权，确认不绕过 RBAC/额度 | [协议与任务拆解](plans/2026-09-16-T27-AI助手后端安全加固实施计划.md) | 生效 |
| 2026-09-29 | **写确认门全链路收口（T27.5–T27.8）**：原子确认（Lua 单次消费 + 计划/摘要一致性 fail-closed）、引擎写工具改 prepare 并删直接写工具、前端确认卡片、真机联调通过（正向两项落库核对 + 双击/取消/刷新负向；过期重放由真 Redis 集成 9 例覆盖）；卡片不进历史回放属设计内 | [联调证据](plans/records/T27.8-联调收口.md) | 生效 |
| 2026-09-29 | **写确认门独立 review：有条件通过**（0 条 P1）——单次消费、执行即既存计划、绑定用户/会话/摘要、不绕过 RBAC 与额度经代码审读 + 真 Redis 9 例复跑成立；**2 条 P2 待用户批**（确认请求吃 10s 客户端超时致卡片谎报失败、卡片只给 id/条数无法人工核对）；3 项判已知限制关闭 | [报告与未验证项](decisions/2026-09-29-review-write-gate.md) | 生效（有条件通过） |
