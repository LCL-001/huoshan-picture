# Spec — 火山图库（huoshan-picture）

<!-- freshness: { owner: "lcl", reviewed: "2026-09-28" } -->

> AI 维护本文档；用户只须确认下方"关键三行"。三行未确认前，不允许写任何实现代码。
> **三行只写稳定意图**：不写日期、不写"已完成/已上线"等状态、不写进展。状态在 docs/plan.md，沿革在 docs/decisions.md；三行只在意图变化时改动（门禁会检查三行纯度）。

## ⚡ 关键三行（唯一必须用户亲自看的部分）

- **目标：** 图片与素材管理平台——个人/团队按"空间"管理图片（空间级 RBAC、图片列表多级缓存、批量抓取入库、AI 扩图、多人实时协同编辑；存量功能清单见下方 F1–F9）。内嵌 AI 助手（对话 + SSE 步骤折叠条 + 会话列表与历史回看续聊；图库后端代理，智能体引擎为 `ai/` 下的 MyManus），两个场景 = 智能整理（视觉打标批量整理）+ 选图入库代理；配套全局标签词表（`/tag_category` 查表）。管理员 AI 打标：选中公共图库无标签图 → 服务端自查词表、调多模态模型出建议 → 管理员确认后落库。
- **不做的事：** 已明确排除且不回退：多实例本地缓存脏读、明文凭据入库。**AI 助手不做：** 引擎并进 backend 进程、删除类工具（整理只增改不删）、AI 审核助手、词表按空间隔离、助手会话不做重命名与删除、与图库及图片/素材管理无关的通用问答（无关请求应简短拒绝并引导回能力范围）。**AI 打标不做：** 定时任务或阈值自动跑、普通用户对公共图库打标、复用单张编辑接口、用长期凭据驱动打标。**写安全边界：** 助手写操作（批量改图、按 URL 入库）必须经后端持有的确定性确认门——用户显式确认后仅执行与确认内容逐字段一致的服务端既存计划；模型自然语言判断不构成授权，确认亦不替代登录态、空间 RBAC 与额度校验；写类工具只在确认门可用时开放，可用前助手仅开放查询、搜图候选与建议能力。普通图库页面原有编辑/上传能力不受影响。
- **边界：** `backend/`（Spring Boot 单体，端口 8123，context-path `/api`）、`frontend/`（Vue 3 SPA，开发端口 5173）、`ai/`（`ai/agent` 引擎 SB 3.5/Java 21 + `ai/image-search-mcp-server`，两独立 Maven 应用、独立进程，端口与 8123 错开）；建表脚本 `backend/sql`；密钥一律不入库（`application-local/prod/test.yaml` 被 .gitignore，只入库 `.example` 模板）；源仓库 yu-ai-agent 迁入后冻结归档（tag archive/engine-migrated-to-huoshan）。

## 背景与原始想法

存量项目于 2026-09-12 接入 vibe 工作流（vibe-onboard），原始需求背景未记录，待用户补充原话。

## 现有方案调研（vibe-scout）

> 存量与一期的选型结论：接入前既有选型见 `docs/decisions/legacy-stack.md`；一期各选型见 `docs/decisions/` 与 `docs/designs/2026-09-13-图库智能体一期设计.md`。
> 助手范围围栏改进的轮子调研（2026-09-28，起因 = T36–T39 冒烟的门卫历史污染误拒）：候选与"借鉴"裁决见 `docs/designs/2026-09-28-助手范围围栏开源调研.md`——借鉴 NeMo Guardrails 的"门卫输入只看当前消息 + 拒绝作为对话一等公民"，未采纳引入护栏框架本体；生效决定待门卫改进立项字据时转入 `docs/decisions.md`。

## 功能需求

- [x] F1 用户体系：注册/登录（邮箱验证码）、用户信息管理
- [x] F2 空间与 RBAC：空间成员角色权限（SpaceUserController）
- [x] F3 图片管理：上传（腾讯云 COS）、审核、搜索、批量抓取（Bing 抓取器，契约测试守护改版）
- [x] F4 图片列表多级缓存：Caffeine + Redis、跨实例失效广播、命中率埋点、缓存重建锁预算化
- [x] F5 AI 扩图（阿里百炼）：任务幂等 + 每日配额
- [x] F6 多人协同编辑：WebSocket + Disruptor，编辑锁 + 空闲超时兜底
- [x] F7 帖子与社交（部分停用）：Post/PostInteraction/UserFollow 类注解已注释、不被 Spring 加载；SocialController 仅保留 /notification/*（顶栏铃铛在用），/timeline 已注释停用；前端三个社交路由与页面已清理（T22）
- [x] F8 可观测性：traceId 全链路日志（异步透传）、Actuator/Prometheus 指标
- [x] F9 工程化：GitHub Actions CI（编译 + 无 MySQL/Redis 单测）、JMeter 压测资产（`backend/jmeter/` 入库：计划 + statistics + 脚本 + README；口径以其 README 为准）
- [x] F10 标签词表（T5）：`tag` 表（全局词表）+ `/tag_category` 查表动态化；编辑接口同事务 upsert 词表并累加 usageCount；picture.tags 与 LIKE 查询不动
- [x] F11 AI 助手一期：`ai/` 引擎 + `backend` 代理 + `frontend` 助手界面（对话 + SSE 步骤折叠条）。实现状态、验收证据与已知限制见 `docs/features/F11-AI助手MVP.md`、`F12`、`F13` 与 `docs/plans/records/` 各任务记录
- [x] F12 图库助手档 3 工具与联调：写类工具与看图打标工具集（讲解见 `docs/features/F12-图库助手档3工具与联调.md`）
- [x] F13 对话循环与 SSE 事件协议：循环收敛与协议契约（讲解见 `docs/features/F13-对话循环与SSE事件协议.md`）
- [x] F14 AI 打标：管理员两步式打标（讲解见 `docs/features/F14-AI打标.md`）
- [x] F15 助手快赢包（T36–T39）：会话列表与历史回看续聊（backend `ai_conversation` 索引表 + 引擎只读消息端点 + 前端会话侧栏）、失败以独立错误样式呈现（代理改发协议既有 error 帧）。设计与已知限制见 `docs/designs/2026-09-28-助手快赢包设计.md`
- [ ] F16 助手写确认门（T27.4–T27.8）：批量改图 / 按 URL 入库改走"准备 → 确认 → 执行"协议——模型只提交操作计划（准备阶段零写库），backend 持有计划并生成单次消费凭据，SSE 下发 confirmation_required，用户显式确认后原子执行与确认卡片逐字段一致的既存计划。协议与任务拆解见 `docs/plans/2026-09-16-T27-AI助手后端安全加固实施计划.md` T27.4–T27.8

## 技术方案

详见 AGENTS.md"项目速览"。数据脚本：backend/sql/create_table.sql、add_user_email.sql；本地配置模板：backend/src/main/resources/application-local.yaml.example。

## 里程碑

- M1 AI 助手一期（2026-09-13 立项 → 2026-09-17 首次生产部署）已收官，全程归档见 `docs/plans/archive/2026-09-AI助手一期.md`。
