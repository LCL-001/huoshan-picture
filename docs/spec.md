# Spec — 火山图库（huoshan-picture）

> AI 维护本文档；用户只须确认下方"关键三行"。三行未确认前，不允许写任何实现代码。

## ⚡ 关键三行（唯一必须用户亲自看的部分）

- **目标（2026-09-12 用户确认；2026-09-13 / 2026-09-15 两次增补同批确认）：** 图片与素材管理平台——个人/团队按"空间"管理图片（空间级 RBAC、图片列表多级缓存、批量抓取入库、AI 扩图、多人实时协同编辑；存量功能清单见下方 F1–F9）；已上线 https://www.lincode.online。**AI 助手一期（2026-09-13 立项）**：内嵌助手界面（对话 + SSE 步骤折叠条）+ 图库后端代理，智能体引擎为 `ai/` 下的 MyManus；两个场景 = 智能整理（视觉打标批量整理）+ 选图入库代理；配套全局标签词表（`/tag_category` 从硬编码改查表）。**AI 打标（2026-09-15 立项）**：管理员的图库侧能力——选中公共图库无标签图 → 服务端自查词表、调多模态模型出建议 → 管理员确认后落库（两步式，只改 `tags` 与 `category`）。一期按档位推进（档 1 headless → 档 2 用户可见 MVP → 档 3 完整一期），**三档已于 2026-09-15 全部完成**；推进与转向史见 `docs/plan.md`、`docs/decisions/`。
- **不做的事（存量部分待用户补充原话；AI 部分已确认）：** 存量接入的原始排界未记录。已明确排除且不回退：多实例本地缓存脏读（已用跨实例广播解决）、明文凭据入库。**一期 AI 助手不做：** 引擎并进 SB 2.7 backend 进程（Spring AI 栈要求 SB 3.x，只能独立进程）、删除类工具（整理只增改不删）、AI 审核助手（另行立项）、词表按空间隔离（全局词表起步）。**AI 打标不做：** 定时任务或"待打标数量达阈值即自动跑"、普通用户对公共图库打标、复用单张编辑接口、用管理员的长期凭据驱动打标（凭据不得驻留）。
- **边界（AI 依仓库结构起草，2026-09-13 R2 修订已确认）：** `backend/`（Spring Boot 单体，端口 8123，context-path `/api`）、`frontend/`（Vue 3 SPA，开发端口 5173）、`ai/`（`ai/agent` 引擎 SB 3.5/Java 21 + `ai/image-search-mcp-server`，两独立 Maven 应用、独立进程，端口与 8123 错开）；建表脚本 `backend/sql`；**密钥一律不入库**（`application-local/prod/test.yaml` 被 .gitignore，只入库 `.example` 模板）；源仓库 yu-ai-agent 随迁入完成冻结归档。

## 背景与原始想法

存量项目于 2026-09-12 接入 vibe 工作流（vibe-onboard），原始需求背景未记录，待用户补充原话。

## 功能需求（存量已实现，从 README 与提交记录归纳）

- [x] F1 用户体系：注册/登录（邮箱验证码）、用户信息管理
- [x] F2 空间与 RBAC：空间成员角色权限（SpaceUserController）
- [x] F3 图片管理：上传（腾讯云 COS）、审核、搜索、批量抓取（Bing 抓取器，契约测试守护改版）
- [x] F4 图片列表多级缓存：Caffeine + Redis、跨实例失效广播、命中率埋点、缓存重建锁预算化
- [x] F5 AI 扩图（阿里百炼）：任务幂等 + 每日配额
- [x] F6 多人协同编辑：WebSocket + Disruptor，编辑锁 + 空闲超时兜底
- [x] F7 帖子与社交（部分停用）：Post/PostInteraction/UserFollow 控制器类注解已注释、不被 Spring 加载；SocialController 在服务但仅保留 /notification/* 通知接口（前端 GlobalHeader 铃铛正在调用，用户 2026-09-13 拍板保留），/timeline 已注释停用。**前端残留（2026-09-15 排查，同日 T22 已清理）**：原先 frontend 仍注册 `/square`、`/post/:id`、`/user/:id` 三个路由并引用已停用的 `/post/**`，`@/api/postController.ts` 里混着**仍在服务**的手写通知函数（不可整文件删）——现在三个路由与三个页面已删、`postController.ts` 已整体删除（6 个通知函数迁入 `api/notificationController.ts`，顶栏铃铛照常工作）。
- [x] F8 可观测性：traceId 全链路日志（异步透传）、Actuator/Prometheus 指标
- [x] F9 工程化：GitHub Actions CI（编译 + 无 MySQL/Redis 单测）、JMeter 压测资产（本地，不入库）
- [x] F10 标签词表（2026-09-14 T5 完成）：新表 `tag`（id/name/type(tag|category)/usageCount/审计字段，全局词表；列名随库内 camelCase 约定，设计文档速写 usage_count）；/tag_category 查表动态化（返回结构 PictureTagCategory 逐字段不变，9 标签 + 5 分类种子迁移）；编辑接口（单编/批编）同事务 upsert 词表并累加 usageCount（一次编辑调用计 1，不随图片数放大）；picture.tags JSON 列与 LIKE 查询逻辑不动
- [ ] F11 AI 助手一期（**2026-09-15 用户拍板：等一期部署上线后再勾**；代码已推进到 T24，尚未上线）：`ai/` 引擎（图库工具集、OpenAI 协议多模型、会话类型 + headless SSE 端点、MCP 搜图）+ `backend` AI 代理（服务间 API key、凭据组透传、SSE 转发）+ `frontend` 助手界面（对话 + SSE 步骤折叠条）。**实现状态、验收证据与已知限制见 `docs/features/F11-AI助手MVP.md`、`F12`、`F13`，以及 `docs/plans/records/` 下 T4–T24 各任务记录。**

## 技术方案

详见 AGENTS.md"项目速览"。数据脚本：backend/sql/create_table.sql、add_user_email.sql；本地配置模板：backend/src/main/resources/application-local.yaml.example。

## 里程碑

存量项目未定义里程碑——当前进行中的方向见 docs/plan.md（推断，待用户确认）。
