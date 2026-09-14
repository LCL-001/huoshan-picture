# Plan — 火山图库（huoshan-picture）

> 任务粒度：一个任务 = 一次可测试的改动 = 一个 commit。每个任务必须标注允许触碰的文件范围。
>
> 2026-09-13 **AI 助手一期立项**（设计文档 docs/designs/2026-09-13-图库智能体一期设计.md）：**同日 R2——MyManus 引擎与 image-search-mcp-server 整体迁入本仓库（ai/ 独立应用），一期全部任务在本仓执行（T4-T12，另 T13 可选）**。

## 任务清单（AI 助手一期，2026-09-13 立项，R2 单仓化）

- [x] T4 AI 引擎迁入（2026-09-13 完成）：ai/agent（端口 8124）与 ai/image-search-mcp-server（8127）原样迁入；顺带修复 ImageSearchTool.java UTF-8 BOM 编译错误（源仓遗留）；ai/agent 55 用例基线在新位置全绿；门禁已纳入 ai/agent 单测（负向探针验证会拦）；双服务冒烟通过（8124 /api/health → ok、8127 SSE 启动正常）；源仓库 yu-ai-agent 已归档（handoff 2026-09-13-引擎迁出归档.md + tag archive/engine-migrated-to-huoshan；用户 README 手改已代提交 8a5f450）。实施计划：docs/plans/2026-09-13-T4-AI引擎迁入实施计划.md（迁移提交 88b50bf / f1c4a1a / 4560fd2）
- [x] T5 标签词表（2026-09-14 完成）：新表 `tag`（id/name/type(tag|category)/usageCount/is_delete/create_time/update_time，全局词表不按空间拆）+ Tag entity/mapper/service + /tag_category 查表动态化（返回结构逐字段不变，9 标签 + 5 分类种子迁移）+ 编辑接口（单编/批编）同事务 upsert 词表并累加 usageCount
  - 文件范围：backend/sql 新建表脚本、domain（Tag）、mapper、service、PictureController.listPictureTagCategory、PictureServiceImpl 编辑两处
  - 验收：集成测试（动态词表返回/种子迁移/upsert 计数/返回结构兼容）+ 门禁绿 + 全量回归
  - 实施记录：提交 be1c006（计划）/ a68d8cc（建表脚本+域模型）/ 647f77f（词表服务+集成测试）/ 4ed0025（动态化+编辑接线）；实施计划 docs/plans/2026-09-13-T5-标签词表实施计划.md；功能讲解 docs/features/F10-标签词表.md；全量回归 65 用例绿（62 存量 + 新增 TagVocabularyIntegrationTest 3 用例）；实现备注——upsert 采用"先原子自增、未命中插入、并发首插撞 uk_name_type 退化自增"（无自定义 SQL）；单编 editPicture 因"同事务"要求补加 @Transactional；列名 usageCount 与设计文档速写 usage_count 的微差随库内 camelCase 列约定（features 文档已记）；执行偏差——批编接线测试因先红后绿的正确顺序由 Task 2 调整至 Task 3（计划文档已补执行记录）
- [x] T6 OpenAI 协议模型接入（2026-09-14 完成）：新增 `spring-ai-starter-model-openai:1.1.2` 依赖；两套独立配置 `app.ai.openai.assistant`（主脑）/ `.vision`（visionTagger），装配为 `OpenAiChatModels` 持有器（**刻意不注册 ChatModel Bean**，避免与 profile 默认模型及 ChatClient.Builder 自动装配按类型冲突）；`application.yaml` 显式关掉 OpenAI 其余模型类型自动装配（六条自动装配均 matchIfMissing=true）；普通会话 Ollama 默认链路零改动
  - 文件范围：ai/agent 的 pom、application.yaml（+example）、模型装配 config
  - 验收：单测（装配/配置绑定）+ 本地冒烟
  - 实施记录：提交 4b0d527（计划）/ b946a42（依赖 + 守卫 + 配置骨架）/ 5730e7d（装配三件套 + 7 单测）/ b5edf74（base-url 示例修正 + 装配日志）；实施计划 docs/plans/2026-09-14-T6-OpenAI协议模型接入实施计划.md；门禁口径 62 用例绿（55 存量 + 7 新增），守卫测试做过负向探针（删属性 / 置 openai 均红）；冒烟实测——只加依赖不加守卫时启动即崩（`openAiAudioSpeechModel` 创建失败：OpenAI API key must be set），加守卫后六条 OpenAI 自动装配全落 Negative matches 且 /api/health=ok；用本机 Ollama 的 OpenAI 兼容端点（base-url `http://localhost:11434` + `qwen3:4b`）真实调用返回「收到」，默认会话 `/api/ai/manus/chat` SSE 正常（answer + [DONE]）；关键坑——base-url **不能带尾部 /v1**（Spring AI 请求路径自带 `/v1/chat/completions`，实测带 /v1 会 404），已写进配置注释与 .env.example
  - 独立 review（规则 13，2026-09-14，独立会话只读复核，证据见该会话报告）：**有条件通过**——交付本体成立（① 六条 OpenAI 自动装配的条件反查为 `@ConditionalOnProperty(spring.ai.model.<type>=openai, matchIfMissing=true)`，与守卫一一对应，运行时条件报告六条全 Negative；② 持有器不注册 ChatModel Bean 的依据反查为真：`ChatClientAutoConfiguration#chatClientBuilder` 的 `ChatModel` 形参按类型取单个，容器实有 `ollamaChatModel`（ollama profile），再注册即不唯一；③ base-url 带/不带 `/v1` 的请求路径实测为 `/v1/chat/completions` 与 `/v1/v1/chat/completions`；④ 半配置在真实上下文启动即失败且消息清晰；⑤ 装配进真实上下文（传参启动）后 `OpenAI 协议模型就绪` 日志与 /api/health=ok 同时成立，默认链路零扰动；⑥ 门禁口径 62 用例含新增 7 条；⑦ T6 三笔代码提交无任何密钥入内容，`.env`/`application-local.yaml` 仍被 .gitignore 覆盖）
  - review 发现（P1/P2/P3 全量口径）：**P1** 流式路径无任何超时（见 T6.1）；**P2** "超时已生效"无测试守护——7 条单测无一断言超时真的接到 HTTP 客户端上，这正是 P1 漏网的原因，随 T6.1 补反射断言；**P3**（可接受，记已知限制）① 守卫测试只读 `application.yaml`，看不到 profile 覆盖（若未来某 profile 把 `spring.ai.model.chat` 置回 `openai`，测试仍绿；但因实际后果是启动即崩，属响亮失败）；② 守卫是"按上游当前六类型"的清单式，升级 Spring AI 时上游新增第七类模型类型会静默回到 `matchIfMissing=true` 启用（已核 1.1.8 与 1.1.2 的 imports 清单相同，均为六条；建议把"比对 `META-INF/spring/...AutoConfiguration.imports`"写进 Spring AI 升级检查项）；③ `timeout` 裸数字按毫秒绑定（实测 `timeout: 60` → `PT0.06S`，`60s` → `PT1M`），建议加 `@DurationUnit(SECONDS)` 或注释写明单位；④ 只配 base-url（无 api-key/model）被静默忽略，与"半配置拒绝"不对称（实测 `configured=false partial=false`，不报错也不生效），建议文档写明；⑤ `spring.ai.model.{image,audio.speech,audio.transcription}` 与 DashScope starter 同名共享，这四条守卫同时也关掉 DashScope 对应装配（ollama profile 下本已被 `spring.ai.dashscope.enabled=false` 关掉、且无代码消费这些 Bean，无实际影响；yaml 注释"OpenAI starter 的这四类"宜补一句共享说明）；⑥ `OpenAiChatModelsTest` 夹具里的 base-url 仍带 `/v1`（该文件 :21、:26），与 b5edf74 修正后的口径相反，建议顺手改成不带 `/v1` 的形态；⑦ 半配置 fail-fast 会拒掉"全局只注入一半变量"的部署姿势（已实测：`BeanCreationException ... app.ai.openai.vision 配置不完整`，属有意设计，记为已知限制）
- [ ] T6.1（P1，**T7/T8 开工前必做**）流式路径超时保护（**两件事，缺一不可**）：① `OpenAiChatModels` 装配 `OpenAiApi` 时补 `webClientBuilder(...)`，给 `WebClient` 的 connector 设连接与响应头超时——覆盖"连不上 / 首包不来"；② 在流上补**空闲超时**（`Flux.timeout(Duration)` 逐信号空闲超时，或 T8 事件循环里的看门狗）——覆盖"吐了几段之后静默"。并补单测钉住"阻塞与流式两条路径的超时均已接到 HTTP 客户端上，且流侧有空闲超时"
  - 现象：`OpenAiApi.Builder` 无参构造默认 `webClientBuilder = WebClient.builder()`（字节码），而 `OpenAiChatModels#build` 只调 `restClientBuilder(...)`
  - 证据（T6 review 实测，本机探针）：非流式 `chatCompletionEntity` 走 restClient——`timeout=3s` 时 3046 ms 抛 `java.net.SocketTimeoutException: Read timed out`；流式 `chatCompletionStream` 走 webClient——同一端点、同一 3s 配置，25 s 后仍 pending（无超时、无报错）。反射实测 `JdkClientHttpConnector.readTimeout=null`、底层 `java.net.http.HttpClient.connectTimeout=Optional.empty`（本项目 classpath 无 reactor-netty/jetty/HC5，`WebClient.builder()` 的 connector 探测按 `DefaultWebClientBuilder#initConnector` 字节码顺序落到 JdkClientHttpConnector）
  - 为什么只给 webClient 接超时不够（review 二次实测，connector `readTimeout=3s` 三形态）：**稳态吐字的长流不被误杀**（1 chunk/s、6 段的流 3.4 s 正常 onComplete 全 6 段 → 它不是"整响应死线"）；**完全不响应的端点 3020 ms 抛** `WebClientRequestException: java.net.http.HttpTimeoutException: request timed out`（它等价于 JDK `HttpRequest.timeout`，只覆盖到**响应头到达**）；**吐 2 段后静默的流 20 s 仍 pending、无任何异常**——故"流中途卡死"这一类必须由流侧空闲超时兜住
  - 影响：T8 把对话循环收敛为"仅 SSE 流式"后，上游一次卡死即永久挂住 SSE 连接与请求线程（TCP 不断开、无字节）；且不产生异常，Spring AI 重试模板（仅 retryOn `TransientAiException`）无从介入
  - 验收：单测（阻塞路径 requestFactory 超时 + 流式 connector 超时 + 流空闲超时三处断言）+ 冒烟（无响应 / 吐一半静默两种形态均在超时内失败，且稳态长流不被误杀）
  - 文件范围：ai/agent 的 config/OpenAiChatModels.java + 对应测试（②若落在 T8 事件循环实现，文件范围随之加 T8 的循环文件）
  - 口径备注（2026-09-14 追加）：**不要用"保留 `BaseAgent.run()` 阻塞路径"当作本缺口的兜底**——两条路径的差异在 HTTP 客户端（restClient 有超时 / webClient 没有），不在同步与否；SSE 是产品必需（前端折叠条 + headless 端点），保留 run() 不改变生产流量走哪条路，只是把 T8 要消灭的第二套循环长期留下（与用户 2026-09-13 R2 拍板相反）。若将来确实要"非流式入口"，按设计文档既有口径在事件流之上做收集实现，而非保留 run() 本体
- [ ] T7 图库工具集（ai/agent）：`tools/huoshan/` 新增——listSpaces / listPictures / getTagCategory / batchEditPictures / batchUploadByUrl（RestClient 封装图库 API，prototype 化 per-request 注入透传 token 与图库地址）+ visionTagger（OpenAI 协议多模态批量看图，提示词注入词表）；粗粒度原则，一期不注册删除类工具
  - 文件范围：ai/agent 的 tools 新增包、必要 config/DTO
  - 验收：单测（Mock 图库 HTTP + visionTagger 打标解析）进门禁绿
- [ ] T8 会话类型机制 + headless 端点（ai/agent）：会话类型（普通/图库助手）决定工具注册表与系统提示词；图库助手 SSE 端点——服务间 API key 鉴权、`huoshan:<userId>` 外部身份映射、satoken 上下文传递给工具集。**随任务执行对话循环收敛（用户 2026-09-13 拍板"对话保留 SSE 模式即可"）：抽事件消费者接口统一 run/runStream 双循环，同步 run() 退役（存量调用方迁移到事件流收集口径），lastStepKind 等受保护字段通道收编进事件载荷，SSE 事件协议（step/answer/metrics/[DONE]）固化为显式契约**
  - 文件范围：ai/agent 的 BaseAgent/ReActAgent/ToolCallAgent 循环重构、agent 装配类、controller 新增端点、config、受影响测试迁移
  - 验收：单测（鉴权/身份映射/工具装配/事件协议契约）+ SSE 端点冒烟；门禁绿
- [ ] T9 MCP client 接入（ai/agent）：spring-ai-starter-mcp-client（已在 pom）连接 ai/image-search-mcp-server（传输方式实施时定）；Pexels 搜图工具进图库助手工具集；MCP 服务本体零改动
  - 文件范围：ai/agent 的 application.yaml、MCP client 配置/装配
  - 验收：单测 + 本地双进程冒烟（搜图返回 URL 列表）
- [ ] T10 图库后端 AI 代理模块：新增代理端点（转发对话请求到 ai/agent headless SSE 端点）——服务间 API key 认证（配置化，密钥不入库）、当前用户 satoken 透传、用户标识（huoshan:<userId>）传递、SSE 流转发（SB 2.7 实现选型实施时定：SseEmitter/OkHttp EventSource）
  - 文件范围：backend 的 controller 新增代理端点、service/manager 新增代理与转发组件、config、application-local.yaml.example
  - 验收：集成测试（API key 校验/satoken 透传）+ 与 ai/agent 冒烟联调
- [ ] T11 前端助手界面：AI 助手入口 + 对话面板 + SSE 步骤折叠条（消费 ai/agent 步骤事件协议语义，AntD 组件渲染）；复用现有登录态
  - 文件范围：frontend/src 新增助手页面/组件、路由入口、openapi 生成代码如需
  - 验收：npm run type-check + lint 过；对话与折叠条人工验收
- [ ] T12 一期联调验收：跑通两条场景（智能整理/选图入库）；按规则 9 写功能讲解文档 docs/features/
  - 文件范围：联调发现的必要小修 + docs/features/
  - 验收：设计文档"验收标准"4 条全过；门禁绿 + 全量回归
- [ ] T13（可选）引擎教学遗留清理（承接原 yu-ai-agent T1）：AuthAdvisor 假实现、demo 包、空壳控制器（ChatMessage/ChatSummary）、FileBasedChatMemory 死代码、空目录
  - 文件范围：ai/agent 内上述文件与目录
  - 验收：编译 + 门禁绿

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
