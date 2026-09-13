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
