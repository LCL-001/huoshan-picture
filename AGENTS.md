# AGENTS.md — 火山图库（huoshan-picture）

> 本文件是所有 AI 编码工具（ZCode / Cursor / Claude Code…）的统一契约。接手任何新会话，先完成"接手流程"再动代码。用 Claude Code 时可在 CLAUDE.md 里写一行 `@AGENTS.md` 引入本文件；Cursor 原生读取 AGENTS.md。

## 接手流程（新会话必做，按序）

1. 通读本文件
2. 读 docs/spec.md 开头的"关键三行"
3. 读 docs/handoff/ 下最新一份快照
4. 全部读完之前，不写任何代码

## 项目速览

- 一句话：图片与素材管理平台——按"空间"管理图片、空间级 RBAC 成员权限、图片列表多级缓存、批量抓取、AI 扩图与多人实时协同编辑；已上线 https://www.lincode.online
- 技术栈：
  - 后端（backend/）：Java 17、Spring Boot 2.7.6、MyBatis-Plus、MySQL 8、Redis、ShardingSphere（分库分表）、Sa-Token（认证，会话存 Redis）、Caffeine + Redis 两级缓存、WebSocket + Disruptor（协同编辑）、腾讯云 COS（对象存储）、阿里百炼（AI 扩图）、Actuator + Prometheus（可观测）
  - 前端（frontend/）：Vue 3、Vite、TypeScript、Pinia、Ant Design Vue、ECharts
- 架构要点：前后端单仓库。后端经典分层 `controller → service → manager → mapper`：manager 收敛第三方与复杂组件（auth、cache、crawler、observability、sharding、upload、websocket、CosManager）；公共返回/异常在 common、exception、annotation/aop。注意：controller 中 Post/PostInteraction/Social/UserFollow 属于**停用模块**（类注解已注释，不被 Spring 加载，用户 2026-09-12 确认）。建表脚本在 backend/sql；部署文档与 nginx 配置在 backend/docs/deploy；接口文档走 Knife4j。CI 在 .github/workflows/ci.yml。

## 命令

- 安装：
  - 后端：`cd backend` → `cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml`（填入 MySQL 口令、COS 密钥、百炼 API Key）→ `mvn -B package -DskipTests`
  - 前端：`cd frontend && npm install`
- 开发：
  - 后端：`java -jar backend/target/yun-picture-base-0.0.1-SNAPSHOT.jar`，默认 http://localhost:8123/api（Windows 下勿用 `mvn spring-boot:run`，会报 CreateProcess error=206）
  - 前端：`cd frontend && npm run dev`，http://localhost:5173（后端地址由 frontend/.env.development.local 的 `VITE_API_BASE` 指定）
- 测试：
  - 后端单测（不依赖 MySQL/Redis，与 CI 同口径）：`cd backend && mvn -B test -Dtest='!*IntegrationTest,!RedisStringTest,!YunPictureBaseApplicationTests' -DfailIfNoTests=false`
  - 约定：需要 MySQL/Redis 的测试类以 `IntegrationTest` 结尾；新增纯单测会被 CI 自动纳入，无需改 workflow
  - 前端：无测试脚本；以 `npm run type-check`（vue-tsc）和 `npm run lint`（eslint）作为检查手段
- 前端 OpenAPI 代码生成：`cd frontend && npm run openapi`
- 门禁启用：新克隆仓库后执行一次 `git config core.hooksPath .githooks`，pre-commit 测试门禁才生效

## 工作规则（认真轨：硬）

1. **改动范围**：只允许修改当前任务（docs/plan.md 对应条目）明确涉及的文件。禁止顺手重构、禁止"顺便优化"无关代码。发现必须改的问题 → 停下报告，等用户决定。
2. **决定不翻案**：动手前先读 docs/decisions.md。已确认的决定不得推翻；确有必要推翻时停下询问用户，同意后把变更记入 decisions.md。
3. **大需求先立字据**：用户新提的需求超出当前 spec 时，先更新 docs/spec.md 与 docs/plan.md，等用户确认"关键三行"后再动代码。
4. **红绿灯**：每完成一个任务：补/改测试 → 跑绿 → 单独 commit（conventional 一行式）。git 门禁会拦截红测试的提交，不要尝试绕过。
5. **逃生舱**：测试连续 2 轮修不红 → 立即停止，汇报现象、已尝试的方案、当前猜测，等用户指令。
6. **交接**：会话结束、用户要求交接、或即将换工具时 → 按 vibe-handoff 流程写 docs/handoff/ 快照。
7. **文档提交**：AGENTS.md / NOTES.md / docs/** 的改动一律用 `docs:` 前缀单独提交，不与代码改动混在一个 commit（例：`docs: handoff 2026-09-12 定级讨论`）；纯文档 commit 门禁自动跳过测试。
