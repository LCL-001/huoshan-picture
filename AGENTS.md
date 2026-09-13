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
  - 后端（backend/）：Java 17、Spring Boot 2.7.6、MyBatis-Plus、MySQL 8、Redis、ShardingSphere（分库分表，已注释停用预留，用户 2026-09-13 拍板）、Sa-Token（认证，会话存 Redis）、Caffeine + Redis 两级缓存、WebSocket + Disruptor（协同编辑）、腾讯云 COS（对象存储）、阿里百炼（AI 扩图）、Actuator + Prometheus（可观测）
  - 前端（frontend/）：Vue 3、Vite、TypeScript、Pinia、Ant Design Vue、ECharts
- 架构要点：前后端单仓库。后端经典分层 `controller → service → manager → mapper`：manager 收敛第三方与复杂组件（auth、cache、crawler、observability、sharding、upload、websocket、CosManager）；公共返回/异常在 common、exception、annotation/aop。注意：controller 中 Post/PostInteraction/UserFollow 属于**停用模块**（类注解已注释，不被 Spring 加载，用户 2026-09-12 确认）；SocialController 本体在服务，仅保留 /notification/* 通知接口（前端 GlobalHeader 铃铛在用，/timeline 已注释停用，用户 2026-09-13 确认）。分库分表 ShardingSphere 已注释停用（主类 exclude 自动配置，yaml 分表块已注释，见 docs/decisions.md）。建表脚本在 backend/sql；部署文档与 nginx 配置在 backend/docs/deploy；接口文档走 Knife4j。CI 在 .github/workflows/ci.yml。

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
7. **文档提交**：AGENTS.md / NOTES.md / docs/**（以及 .gitattributes / .githooks/**）的改动一律用 `docs:` 前缀单独提交，不与代码改动混在一个 commit（例：`docs: handoff 2026-09-12 定级讨论`）；纯文档 commit 门禁自动跳过测试。
8. **方向转向**：用户说"方向不对 / 推倒重来 / 换个方向"时，按三步执行，顺序不可乱：
   - **封存**：先打 tag `archive/<旧方向主题>`（或开归档分支），旧代码此刻不删；
   - **改锚**：小转向 → 更新 docs/spec.md 关键三行并增删 docs/plan.md 任务，等用户放行；大转向 → spec 重写、逐模块重建，旧代码删除放在最后一步；
   - **记因**：在 docs/decisions.md 记一条"旧方向废弃，原因 <X>"。缺了这一条，规则 2 会把旧方向当作生效决定反复提起。
9. **功能讲解**：每完成一个功能（plan 中带验收的功能级任务），在 docs/features/ 写一篇讲解文档（`F<n>-<功能名>.md`，模板：是什么/怎么用/怎么实现/怎么验证/已知限制），紧跟一次 `docs: feature <功能名>` 提交。**改动既有功能前，先读它的讲解文档**（没有就先补写再动手）。
10. **编码纪律**：新建文件一律 UTF-8（无 BOM）；代码读写文件显式指定编码，不依赖系统默认（中文 Windows 默认 GBK，混用必乱码）；`.sh`/hook 类脚本保持 LF（.gitattributes 已锁）；工具脚本的控制台输出用 ASCII（Windows 控制台默认 GBK，中文常乱码）；新建项目目录名用纯 ASCII。
11. **命令行纪律**：执行命令前先确认目标 shell（cmd / PowerShell / bash），按其语法来——cmd 不支持 `;` 链接（用 `&`）也不支持多行输入，命令一律写成单行；含空格或中文的路径必须加引号；命令失败先排查 shell 语法和编码，再怀疑程序本身。
12. **回归测试**：修任何 bug 的顺序固定为——先写复现测试（必须先看到红）→ 修复到绿 → 该测试永久保留。禁止靠删除或跳过既有测试让门禁变绿；测试套件变大变慢后，门禁可指向快速子集，全量回归交给 CI 或定期手动执行。
13. **独立 review**：完成大功能或大重构后，**建议用户新开一个会话（或换一个工具）只做 review**：只读 AGENTS.md、spec 关键三行和该功能的讲解文档，专挑"做得对不对"（设计、边界、安全、与 spec 的一致性），不复述实现过程。review 结论记入 docs/decisions.md 或该功能文档的"已知限制"。红绿灯只能证明"跑得对"，review 才管"做得对"。
