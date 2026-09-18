# 存量选型（接入前既有）

> 自 docs/decisions.md 外移（2026-09-18 文档保养）；**逐字保留**。这些是接入 vibe 工作流之前就存在的技术选型，
> 同样受"决定不翻案"约束；状态列原为"生效（存量）"。

| 日期 | 决定 | 详情 | 状态 |
|---|---|---|---|
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
