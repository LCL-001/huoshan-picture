# Decisions — 火山图库（huoshan-picture）

> 已确认的决定记录在案，AI 不得推翻；翻案必须先经用户同意并在此追加一条变更记录。

| 日期 | 决定 | 理由 | 状态 |
|---|---|---|---|
| 2026-09-12 | 定级：认真轨 | 存量项目已上线运营，用户主动要求接入 vibe 工作流，仓库已有 CI 与测试 | 生效 |
| 存量 | 前后端单仓库（backend/ + frontend/） | cc6fd6f 初始化单仓库 | 生效（存量） |
| 存量 | Spring Boot 2.7.6 + Java 17 + MyBatis-Plus | 项目起点选型（pom.xml） | 生效（存量） |
| 存量 | 分库分表用 ShardingSphere 5.2.0 | pom 依赖 + manager/sharding | 生效（存量） |
| 存量 | 认证用 Sa-Token，会话存 Redis（sa-token-redis-jackson） | pom 依赖 | 生效（存量） |
| 存量 | 两级缓存 Caffeine（本地）+ Redis，键前缀统一 `huoshantuku:` | fe71e78/596cb8f 统一前缀并迁移存量数据，消除两套命名 | 生效（存量） |
| 存量 | 协同编辑 WebSocket + Disruptor 无锁队列 | pom 依赖 + manager/websocket | 生效（存量） |
| 存量 | 对象存储腾讯云 COS；AI 扩图阿里百炼 | README + manager/upload、CosManager | 生效（存量） |
| 存量 | 集成测试类以 `IntegrationTest` 结尾；CI 排除 `!*IntegrationTest,!RedisStringTest,!YunPictureBaseApplicationTests` | 让无 MySQL/Redis 的干净环境能跑 CI；排除语法用 `!pattern`，新增纯单测自动纳入 | 生效（存量） |
| 存量 | 密钥不入库：application-local/prod/test.yaml 被 .gitignore，模板 application-local.yaml.example 入库 | README + 5faf41f | 生效（存量） |
| 存量 | 提交信息：中文 conventional commits（feat/fix/refactor/test/docs/chore/perf/ci） | git log 全量风格一致 | 生效（存量） |
| 2026-09-12 | 帖子/社交模块（Post/PostInteraction/Social/UserFollow）停用：类注解已注释，不被 Spring 加载 | 用户 2026-09-12 确认 | 生效 |
| 2026-09-12 | 接入 vibe 工作流：AGENTS.md + docs/ 档案 + .githooks pre-commit 测试门禁（门禁复用 CI 同口径单测命令） | 用户要求"接入工作流" | 生效 |
| 2026-09-13 | 对象级鉴权口径：读接口必须绑定目标资源（服务端查出归属再判权，如 checkPictureAuth / spaceUserAuthManager.getPermissionList），禁止依赖 StpInterfaceImpl 从请求嗅探的上下文做空间 scoped 读授权；嗅探层仅作登录/公共图库兜底 | T3.1 修复落定的原则：请求参数可定位资源但不可定权 | 生效 |
| 2026-09-13 | 会话生命周期口径：改密/删号/角色变更必须 StpKit.SPACE.logout(userId) 踢会话；getLoginUser 用 getSession(false)，匿名请求不创建会话 | T3.3：旧会话快照（含 admin 角色）最长 7 天内仍参与空间鉴权 | 生效 |
| 2026-09-13 | 接受残余：Spring Session 在改密后不失效——空间接口全部依赖 Sa-Token 登录态，残余影响限于个人资料编辑等自持操作；如需彻底收敛再做会话版本号机制 | T3.3 收权后评估，量级低 | 生效 |
| 2026-09-13 | T3.6 口径：ShardingSphere 不删除也不启用，注释停用预留——yaml 分表死配置块已注释（主类本就 exclude ShardingSphereAutoConfiguration，配置无人读取），pom 依赖与主类 exclude 保留作为停用护栏与恢复基础，manager/sharding 两类保持休眠；恢复分表前须先解决库名不一致 / 空 range 静默丢数据 / 动态建表回退主表三个坑 | 用户拍板"不删除也不启用，注释起来就行" | 生效 |
| 2026-09-13 | T3.7 口径修订：社交模块通知接口保留——SocialController 的 /notification/* 在服务（前端 GlobalHeader 铃铛正在调用），仅 /timeline 停用；修订 2026-09-12"Social 不被 Spring 加载"的不实记录 | 用户拍板"保留通知并改 spec" | 生效 |
| 2026-09-13 | 维持"CI/门禁排除集成测试"现状：集成测试不入 CI，靠本地全量手动回归（大改动后/发版前必跑）；不采纳"CI 加 MySQL/Redis service 容器跑全量"与"nightly 定时全量" | T3.8-T3.11 独立 review 提出 P3（本批修复的回归锁全在集成测试），用户在三个收口选项中拍板"不动" | 生效 |
| 2026-09-13 | **AI 助手一期立项**：内置 AI 助手（前端界面 + 后端代理），智能体引擎为外部 MyManus（yu-ai-agent 仓库 headless）——代理透传当前用户 satoken（只在内存流转、不落库、无绑定向导）、服务间 API key 认证；一期场景 = 智能整理 + 选图入库；配套建 `tag` 全局词表（/tag_category 从硬编码改查表，返回结构不变；编辑同事务 upsert）；一期不做删除类工具、AI 审核助手、词表空间隔离 | 用户发起"结合图库找落地需求"并逐节定稿（设计文档 docs/designs/2026-09-13-图库智能体一期设计.md，双仓副本）；词表建表依据：标签现为 Controller 硬编码 9 词，后端编辑不校验词表，AI 自由打标需受治理的开放词表 | 生效 |
| 2026-09-13 | **R2：MyManus 引擎与 image-search-mcp-server 整体迁入本仓库**（`ai/agent/` + `ai/image-search-mcp-server/`，独立 Maven 应用不并 reactor、独立进程，端口与 8123 错开；Spring AI 栈无法运行于 SB 2.7 backend 进程，故为同仓不同应用）；后续开发集中本仓库、由本仓库 git 管理；一期任务收敛为本仓 plan T4-T13；源仓库 yu-ai-agent 随迁入验证后冻结归档（其挂账 T1 移交本仓 T13 可选、T2 作废） | 用户拍板"AI 模块移到图库项目目录下，后续在图库目录下工作、由图库 git 管理"——单仓集中管理，避免跨仓协作成本 | 生效 |
| 2026-09-14 | T5 词表落地口径：列名随库内 camelCase 约定记 `usageCount`（设计文档速写 usage_count）；usageCount 语义 = 一次编辑调用 +1（批编不随图片数放大）、只加不减；词表无管理端点、无删除入口（uk_name_type 含已删行，逻辑删除后同名词条不可再注册）；/picture/update（管理员编辑）不接词表 | T5 实施定稿（docs/features/F10-标签词表.md 已知限制、plan.md T5 实施记录） | 生效 |
| 2026-09-14 | T6 模型接入口径：OpenAI 协议两模型（`assistant` 主脑 / `vision` visionTagger）装配为 `OpenAiChatModels` 持有器，**不注册为 ChatModel Bean**；`application.yaml` 显式声明 `spring.ai.model.{image,audio.speech,audio.transcription,moderation}=none`；base-url 填协议根、不带尾部 /v1 | 容器已有 profile 决定的默认 ChatModel，而 `ChatClientAutoConfiguration#chatClientBuilder(ChatModel)` 按类型取单个 ChatModel，多注册一个即 NoUniqueBeanDefinitionException；spring-ai-starter-model-openai 六条模型自动装配全部 matchIfMissing=true（缺省即启用，实测启动崩在 openAiAudioSpeechModel）；Spring AI 请求路径自带 /v1/chat/completions（实测带 /v1 的 base-url 报 404） | 生效 |
| 2026-09-14 | T6 独立 review：**有条件通过** | 交付本体经字节码反查与实测复核成立（守卫恰好覆盖 OpenAI 六条 `spring.ai.model.*` 门、base-url/半配置语义实测与文档一致、装配进真实上下文不扰默认链路），但**流式路径无任何超时**（`webClientBuilder` 未传，实测 `JdkClientHttpConnector.readTimeout=null`，配置 3s 时流式 25s 仍不返回），已立 T6.1（P1，T8 前必补）；另有"超时已生效"无测试守护（P2）与守卫测试看不到 profile 覆盖、timeout 裸数字=毫秒、base-url 单独配置被静默忽略等 P3（见 plan.md T6 review 记录） | 生效 |
| 2026-09-14 | T6.1 流式超时口径（P1/P2 收口）：① 流式 WebClient 显式接 JDK connector（`HttpClient.connectTimeout` + `JdkClientHttpConnector.setReadTimeout`）；② 流空闲超时落在 WebClient 的 `ExchangeFilterFunction` 上、对响应体流逐请求计时（`body.timeout(duration)`），**不用 ChatModel 装饰器**；③ 复用既有 `app.ai.openai.*.timeout` 一把尺（连接 / 响应头 / 流空闲共用，未新增配置键、未动 yaml），0 与负数=不限制 | T6 review P1：connector 读超时等价于 JDK 请求超时、只覆盖到响应头到达（实测吐 2 段后静默 20s 仍挂），"首包不来"与"流中途静默"必须两件事分头兜（两个负向探针各摘一处只有对应形态转红）；装饰器在顶层计时会把"工具执行 + 等下一轮响应"算成静默，逐请求计时才不会误伤 T7/T8 的工具调用长流；0=不限制沿用 SimpleClientHttpRequestFactory 语义并避开 JDK `connectTimeout(0)` 抛错 | 生效 |
