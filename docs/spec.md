# Spec — 火山图库（huoshan-picture）

> AI 维护本文档；用户只须确认下方"关键三行"。三行未确认前，不允许写任何实现代码。

## ⚡ 关键三行（唯一必须用户亲自看的部分）

- **目标（AI 依 README/提交记录起草，2026-09-12 用户确认）：** 图片与素材管理平台：个人/团队按"空间"管理图片，支持空间级 RBAC 成员权限、图片列表多级缓存加速、批量抓取入库、AI 扩图与多人实时协同编辑；已部署上线 https://www.lincode.online。**2026-09-13 增补（AI 助手一期立项）：** 内置 AI 助手——前端助手界面（对话 + SSE 步骤折叠条）+ 后端代理模块，智能体引擎为外部 MyManus；一期场景 = 智能整理（视觉打标批量整理）+ 选图入库代理；配套标签词表建表（/tag_category 从硬编码改查表）。**同日 R2 修订：MyManus 引擎与 image-search-mcp-server 整体迁入本仓库**（`ai/agent/` 与 `ai/image-search-mcp-server/`，独立 Maven 应用、独立进程，不并入 SB 2.7 backend），后续开发集中在本仓库、由本仓库 git 管理。设计全文见 docs/designs/2026-09-13-图库智能体一期设计.md。
- **不做的事（待确认）：** 待确认——存量项目接入，原始排界未记录。从提交记录看已明确排除的：多实例本地缓存脏读（已用跨实例广播解决，不回退）、明文凭据入库（jmx/配置已清理）。**一期 AI 助手不做：** 引擎并进 SB 2.7 backend 进程（Spring AI 栈要求 SB 3.x，只能独立进程）、删除类工具（整理只增改不删）、AI 审核助手（后续另行立项，与 MyManus 仓库冻结的 Listing 合规审查设计同源）、词表按空间隔离（全局词表起步）。
- **边界（AI 依仓库结构起草，待确认）：** backend/（Spring Boot 单体，端口 8123，context-path /api）与 frontend/（Vue 3 SPA，开发端口 5173）；**ai/（R2 迁入：ai/agent SB 3.5/Java 21 引擎 + ai/image-search-mcp-server，独立构建独立进程，端口与 8123 错开）**；建表脚本 backend/sql；部署文档 backend/docs/deploy；密钥不入库（application-local/prod/test.yaml 被 .gitignore，模板 example 入库）。源仓库 yu-ai-agent 随迁入完成冻结归档。

## 背景与原始想法

存量项目于 2026-09-12 接入 vibe 工作流（vibe-onboard），原始需求背景未记录，待用户补充原话。

## 功能需求（存量已实现，从 README 与提交记录归纳）

- [x] F1 用户体系：注册/登录（邮箱验证码）、用户信息管理
- [x] F2 空间与 RBAC：空间成员角色权限（SpaceUserController）
- [x] F3 图片管理：上传（腾讯云 COS）、审核、搜索、批量抓取（Bing 抓取器，契约测试守护改版）
- [x] F4 图片列表多级缓存：Caffeine + Redis、跨实例失效广播、命中率埋点、缓存重建锁预算化
- [x] F5 AI 扩图（阿里百炼）：任务幂等 + 每日配额
- [x] F6 多人协同编辑：WebSocket + Disruptor，编辑锁 + 空闲超时兜底
- [x] F7 帖子与社交（部分停用）：Post/PostInteraction/UserFollow 控制器类注解已注释、不被 Spring 加载；SocialController 在服务但仅保留 /notification/* 通知接口（前端 GlobalHeader 铃铛正在调用，用户 2026-09-13 拍板保留），/timeline 已注释停用
- [x] F8 可观测性：traceId 全链路日志（异步透传）、Actuator/Prometheus 指标
- [x] F9 工程化：GitHub Actions CI（编译 + 无 MySQL/Redis 单测）、JMeter 压测资产（本地，不入库）
- [x] F10 标签词表（2026-09-14 T5 完成）：新表 `tag`（id/name/type(tag|category)/usageCount/审计字段，全局词表；列名随库内 camelCase 约定，设计文档速写 usage_count）；/tag_category 查表动态化（返回结构 PictureTagCategory 逐字段不变，9 标签 + 5 分类种子迁移）；编辑接口（单编/批编）同事务 upsert 词表并累加 usageCount（一次编辑调用计 1，不随图片数放大）；picture.tags JSON 列与 LIKE 查询逻辑不动
- [ ] F11 AI 助手一期：**ai/ 引擎模块（R2 自 yu-ai-agent 迁入）**——图库工具集（prototype 化携带透传 token）、OpenAI 协议多 ChatModel、会话类型 + headless SSE 端点（服务间 API key + `huoshan:<userId>` 身份映射）、MCP client 接入 ai/image-search-mcp-server；**本仓 backend** AI 代理模块（服务间 API key 认证、satoken 透传、SSE 转发，token 只在内存流转不落库）；**frontend** 助手界面（对话 + SSE 步骤折叠条）

## 技术方案

详见 AGENTS.md"项目速览"。数据脚本：backend/sql/create_table.sql、add_user_email.sql；本地配置模板：backend/src/main/resources/application-local.yaml.example。

## 里程碑

存量项目未定义里程碑——当前进行中的方向见 docs/plan.md（推断，待用户确认）。
