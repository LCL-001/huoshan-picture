# AGENTS.md — 火山图库（huoshan-picture）

> 本文件是所有 AI 编码工具（ZCode / Cursor / Claude Code…）的统一契约。接手任何新会话，先完成"接手流程"再动代码。用 Claude Code 时可在 CLAUDE.md 里写一行 `@AGENTS.md` 引入本文件；Cursor 原生读取 AGENTS.md。

## 接手流程（新会话必做，按序）

1. 通读本文件
2. 读 docs/spec.md 开头的"关键三行"
3. 读 docs/handoff/ 下最新一份快照
4. 需要某个任务的细节时，**按 plan.md 条目里的指针**打开 `docs/plans/records/` 或 `docs/decisions/` 下的对应文件；**不要顺序读 plan.md / decisions.md**——它们是索引，细节不在里面
5. 全部读完之前，不写任何代码

## 文档归属（单一事实源）

同一事实**只允许出现在一个文件里**，其它地方写"见 X"。这是硬规则：2026-09-15 复核发现，3 天里的 7 处勘误（例数 138/124、flaky 方向记反、T7 漏勾、已完成任务被列成待办）**全部**源于"同一事实有两份、只改了一份"。

| 文件 | 放什么 | 不放什么 |
|---|---|---|
| docs/spec.md | 关键三行（目标 / 不做的事 / 边界） | 一切实施细节 |
| docs/plan.md | **任务索引**：任务号 + 标题 + 文件范围 + 验收 + 一行状态 + 指针 | 实施记录、红绿输出、证据 |
| docs/plans/records/ | 每个任务的实施记录、红绿输出、负向控制、真机证据 | 影响后续决策的口径 |
| docs/decisions.md | **生效口径表**：日期 + 一句决定 + 状态 + 指针 | 理由与证据长文 |
| docs/decisions/ | 每条决定的完整理由与证据（**逐字保留**，可追溯） | — |
| docs/features/ | 功能讲解（规则 9） | 任务状态 |
| docs/handoff/ | 过程：本次做了什么、为什么、未决、环境残留 | 稳定口径 |
| docs/evidence/ | 可复跑的真机验收文本输出 | 截图、大日志（按规则 16 标"不可复跑"） |

写之前先问一句：**"这句话三个月后还成立吗？"** 成立 → `decisions.md`（口径）；只描述"当时发生了什么" → `handoff` 或 `records`。

## 项目速览

- 一句话：图片与素材管理平台——按"空间"管理图片、空间级 RBAC 成员权限、图片列表多级缓存、批量抓取、AI 扩图与多人实时协同编辑；已上线 https://www.lincode.online；内嵌 AI 助手（一期建设：智能整理 + 选图入库），智能体引擎为 `ai/` 下的 MyManus
- **`ai/` 模块（2026-09-13 自 yu-ai-agent 仓库迁入，独立 Maven 应用不并 reactor）**：`ai/agent/` = MyManus 智能体引擎（Spring Boot 3.5 / Java 21，独立进程，端口 **8124**，context-path /api；与 backend 的 SB 2.7 无法同进程，只能同仓不同应用）；`ai/image-search-mcp-server/` = Pexels 搜图 MCP 服务（端口 **8127**，SSE profile）。迁移记录与一期任务（T4-T12）见 docs/designs/2026-09-13-图库智能体一期设计.md 与 docs/plans/。密钥件（application-local.yaml / .env）不入库
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
  - 前端：`npm run type-check`（vue-tsc）有**存量错误基线**（记在 `frontend/type-check-baseline.txt`，2026-09-15 实测 124 例），口径是"**改动不得净增**"而**不是**"必须全绿"；`npm run lint` 同理只约束改动文件。基线由规则 17 的门禁核对，所以"净增 0"这句话从 2026-09-15 起可以被独立复核。另有 `npm run check:assistant-format`——助手呈现层纯函数的常驻断言（脚本在 `frontend/scripts/`，用例取自真机报文），是本仓前端第一个入库的自动化检查；**新增前端纯函数时优先给它加断言**，别再依赖"净增 0"这一条
- 部署：部署文档与 nginx 配置在 backend/docs/deploy——**注意该目录被 `backend/.gitignore` 的 `docs/` 规则排除，不在版本控制内**，新克隆的仓库里没有这些文件（2026-09-15 复核发现）。CI（.github/workflows/ci.yml）跑编译与无 MySQL/Redis 单测
- 线上验证：https://www.lincode.online
- 前端 OpenAPI 代码生成：`cd frontend && npm run openapi`
- 门禁启用：新克隆仓库后执行一次 `git config core.hooksPath .githooks`，pre-commit 质量门禁（密钥扫描 + 分层测试）才生效

## 工作规则（认真轨：硬）

1. **改动范围**：只允许修改当前任务（docs/plan.md 对应条目）明确涉及的文件。禁止顺手重构、禁止"顺便优化"无关代码。发现必须改的问题 → 停下报告，等用户决定。
2. **决定不翻案**：动手前先读 docs/decisions.md。已确认的决定不得推翻；确有必要推翻时停下询问用户，同意后把变更记入 decisions.md。
3. **大需求先立字据**：用户新提的需求超出当前 spec 时，先更新 docs/spec.md 与 docs/plan.md，等用户确认"关键三行"后再动代码。需求涉及自研较大模块（引擎 / 解析器 / 同步 / 编辑器之类）时，动手前先过一轮 vibe-scout 轮子调研，结论记入 spec 的"现有方案调研"节，避免重复造轮子。
   - **字据只对四类改动强制**：① 推翻已决口径；② 改动 spec 关键三行；③ 引入新依赖 / 新进程 / 新数据表；④ 用户可见行为变更。**其余一律不立字据、不开新任务**——内部加固、文档订正、测试补强、已知限制收口，直接在原任务条目下追加一行实施记录，或按规则 13 关掉。
4. **红绿灯**：每完成一个任务：补/改测试 → 跑绿 → 单独 commit（conventional 一行式）。git 门禁会拦截红测试的提交，不要尝试绕过。
5. **逃生舱**：测试连续 2 轮修不红 → 立即停止，汇报现象、已尝试的方案、当前猜测，等用户指令。
6. **交接**：会话结束、用户要求交接、或即将换工具时 → 按 vibe-handoff 流程写 docs/handoff/ 快照。**一天一份、只写增量**：内容限于"本次新产生的决定 + 未决 + 环境残留"，不复述"当前状态 / 刚完成"（那是 plan.md 与上一份快照的职责）；同一天的多次会话合并进同一份。依据：2026-09-14 一天出过 14 份快照、27 份合计 285 KB，其中复述部分是纯重复。
7. **文档提交**：AGENTS.md / NOTES.md / docs/**（以及 .gitattributes / .githooks/**）的改动一律用 `docs:` 前缀单独提交，不与代码改动混在一个 commit（例：`docs: handoff 2026-09-12 定级讨论`）；纯文档 commit 门禁自动跳过测试。**颗粒度**：字据按规则 3 在动手前单独提交一笔；任务的**收口记录并入 handoff**，不再为"收口"单开一笔提交。依据：2026-09-13 起 145 笔提交中 93 笔（64%）是 docs，其中大量是同一任务的第二、三笔文档提交。
8. **方向转向**：用户说"方向不对 / 推倒重来 / 换个方向"时，按三步执行，顺序不可乱：
   - **封存**：先打 tag `archive/<旧方向主题>`（或开归档分支），旧代码此刻不删；
   - **改锚**：小转向 → 更新 docs/spec.md 关键三行并增删 docs/plan.md 任务，等用户放行；大转向 → spec 重写、逐模块重建，旧代码删除放在最后一步；
   - **记因**：在 docs/decisions.md 记一条"旧方向废弃，原因 <X>"。缺了这一条，规则 2 会把旧方向当作生效决定反复提起。
9. **功能讲解**：每完成一个功能（plan 中带验收的功能级任务），在 docs/features/ 写一篇讲解文档（`F<n>-<功能名>.md`，模板：一句话/怎么用/怎么实现/怎么验证/已知限制；"怎么实现"含关键文件、核心流程、关键设计与理由三小节），紧跟一次 `docs: feature <功能名>` 提交。**改动既有功能前，先读它的讲解文档**（没有就先补写再动手）。
10. **编码纪律**：新建文件一律 UTF-8（无 BOM）；代码读写文件显式指定编码，不依赖系统默认（中文 Windows 默认 GBK，混用必乱码）；`.sh`/hook 类脚本保持 LF（.gitattributes 已锁）；工具脚本的控制台输出用 ASCII（Windows 控制台默认 GBK，中文常乱码）；新建项目目录名用纯 ASCII。
11. **命令行纪律**：执行命令前先确认目标 shell（cmd / PowerShell / bash），按其语法来——cmd 不支持 `;` 链接（用 `&`）也不支持多行输入，命令一律写成单行；含空格或中文的路径必须加引号；命令失败先排查 shell 语法和编码，再怀疑程序本身。
12. **回归测试**：修任何 bug 的顺序固定为——先写复现测试（必须先看到红）→ 修复到绿 → 该测试永久保留。禁止靠删除或跳过既有测试让门禁变绿；测试套件变大变慢后，门禁可指向快速子集，全量回归交给 CI 或定期手动执行。
13. **独立 review**：完成大功能或大重构后，**建议用户新开一个会话（或换一个工具）只做 review**：只读 AGENTS.md、spec 关键三行和该功能的讲解文档，专挑"做得对不对"（设计、边界、安全、与 spec 的一致性），不复述实现过程。红绿灯只能证明"跑得对"，review 才管"做得对"。
    - **输出契约（2026-09-15 起）**：结论只允许两类——**① 现在修**（P1/P2，或廉价且用户可见者）→ 建任务、走正常流程；**② 判为已知限制并关闭**（P3）→ 写进该功能文档的「已知限制」**即算收口：不建任务、不立字据、不写收口记录**。**禁止第三类"挂账"**——挂着比修和关都贵：R5 review 的 6 条 P3 挂了 5 天一条未做，却长期占着 decisions 里 1500 字的一行加 plan.md 一整段。
    - **已知限制的执行**：按"影响面 × 复现成本"排序，**每个里程碑只挑 1~2 条做**，不追求全量清零。
    - 复核范围与判据（含"我没能验证什么"）写进 handoff；**结论本身**（通过 / 有条件通过 + 两类清单）记入 decisions.md 一行。
14. **安全与依赖**：密钥/凭证一律走 `.env`（`.env` 必须进 .gitignore，提交 `.env.example` 作模板），门禁会扫描提交内容中的常见密钥模式；用户输入永不拼进 SQL、命令、HTML/模板；新增依赖前确认包真实存在于官方 registry（防幻觉包/抢注包），写明用途并记入 docs/plan.md；含依赖文件的 commit 会被门禁自动跑依赖审计。（本仓 Maven 栈口径：门禁密钥扫描已生效；依赖审计暂仅覆盖 npm 栈，Maven 侧未纳入。）
15. **编码规范**：新代码贴着既有代码的风格写；命名达意、函数短小、避免三层以上嵌套；不吞错误（catch 后必须处理或上抛，禁止空 catch）；不留死代码和注释掉的代码块。格式以 formatter/lint 工具输出为准，与工具规则冲突时改代码；确需调整工具规则（如 .editorconfig、eslint 配置）须记入 docs/decisions.md。
16. **证据落定**：验收证据分两类落定——**可复跑**（脚本 + 文本输出）贴进 `docs/plans/records/` 或 `docs/evidence/`；**不可复跑**（截图、大日志、一次性探针）不入库，但在 handoff 里显式标注"**不可复跑，证据已过期**"并写清复现步骤。**禁止只写 `%TEMP%\xxx` 这类临时路径作为唯一证据来源**——那些目录会被清掉，后来人（或 review 会话）无从复核。2026-09-15 复核时 27 份 handoff 全部如此，导致只能验证机器可复跑的那部分数字。
17. **文档一致性门禁**：`.githooks/` 的文档一致性检查随 pre-commit 运行（**docs-only 提交也跑**，不启 Maven、秒级）。核对四件事：文档引用的类/方法/文件路径真实存在；文档里的例数与实跑值一致；plan.md 的勾选与正文（有无实施记录）一致；前端 type-check 基线未被突破。**红灯时改文档，或按白名单标注"已知过期"，不改规则、不绕过**；白名单在 `.githooks/docs-consistency-allow.txt`（每行一条 `文件:模式`，需写明为什么允许过期）。
