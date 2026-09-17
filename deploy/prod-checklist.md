# AI 助手一期 —— 生产部署清单（D4）

> 2026-09-15 立。目标：把一期从"代码完成"推到"线上可用"，并把踩坑点写在前头。
> 口径见 `docs/decisions/2026-09-15-D1-loopback.md`；SSE 的 nginx 片段见 `deploy/nginx/ai-assistant-sse.conf`。

## 2026-09-17 首次生产部署结果

- 部署方式：本地打包 JAR / 前端产物后上传服务器，三个 Java 进程交给宝塔 Java 项目管理；backend 用 JDK 17，agent 与 MCP 用 JDK 21。
- 生产引擎库：`huoshan_ai_agent`，4 张表（`user` / `conversation` / `chat_message` / `chat_summary`）已导入。
- **真机踩坑**：环境变量曾写 `MYSQL_USERNAME=huoshan_agent`，但宝塔创建的实际数据库用户是 `huoshan_ai_agent`，导致 `Access denied for user 'huoshan_agent'@'localhost'`。改成实际用户名并重启后，助手恢复。用户名不是固定协议，部署时必须以 `mysql.user` / 宝塔数据库页显示的真实账户为准。
- Java 启动参数顺序：JVM 参数必须放在 `-jar` 前，例如 `java -Xms128m -Xmx512m -jar app.jar --spring.profiles.active=prod`。
- nginx 已由用户合并 SSE / WebSocket / 普通 API 片段；助手线上已能调用。未留下 `nginx -t`、长对话逐帧或 360s 超时的文本证据。

## 0. 先看两个陷阱（会直接把你坑住）

### 陷阱 1：当前的 jar 里烤着**本机**的配置，而后端默认 profile 就是 `local`

实测三个 jar 的 `BOOT-INF/classes/` 内容：

| jar | 内含配置 |
|---|---|
| `backend` | `application.yaml` + **`application-local.yaml`** + **`application-prod.yaml`** + `application-test.yaml` |
| `ai/agent` | `application.yaml` + `application-ollama.yaml` + **`application-local.yaml`** + **`application-prod.yaml`** |
| `image-search-mcp-server` | `application.yaml` + `application-sse.yaml` + `application-stdio.yaml` + **`application-local.yaml`** |

这些 `*-local.yaml` 是 `.gitignore` 的**本机密钥件**（图库的 COS secretId/secretKey、DB 口令、邮箱授权码、百炼 key；MCP 的 Pexels key）。它们不入库，但**会被打进 jar**。

而后端 `application.yaml` 里 `spring.profiles.active: local` 是**默认值**。所以：**拿本机构建的 jar 直接 `java -jar` 起，它会按 `local` profile 加载 jar 里那份本机配置**（连本机库、用本机密钥）。

- 因此**启动脚本必须显式指定 profile**；更稳的做法是**在服务器上重新 `mvn package`**（服务器上没有 `application-local.yaml`，就不会被烤进去）。
- 引擎的默认 profile 是 `ollama`（`application.yaml` 里 `profiles.active: ollama`），**prod 也必须覆盖**，否则走 Ollama 那条链路。

### 陷阱 2：引擎的模型开关 `thinking: disabled` **不在入库配置里**

`extra-body.thinking.type=disabled` 只写在**不入库**的 `application-local.yaml` 里；入库的 `application.yaml` 只有 `base-url` / `api-key` / `model` / `temperature` / `timeout` 几个占位符。

不显式给这个开关的后果：MiMo 进思考模式 → `temperature` 不生效、输出更慢更长（DeepSeek 那边更狠，不关思考模式会在第二轮工具调用直接 400）。**所以 prod 必须自己带上它**（见下面第 3 节的 `--app.ai.openai.*.extra-body...`）。

## 1. 拓扑

```
浏览器 ──HTTPS──> nginx ──> backend 8123（对公网）
                              │  代理 + 服务间密钥 + 凭据组透传（loopback）
                              ▼
                          engine 8124（只监听 127.0.0.1）
                              ├──> 图库 API（以用户自己的凭据，RBAC 由图库判）
                              └──> MCP 8127（只监听 127.0.0.1）
依赖：MySQL（后端 `yu_picture` + 引擎 `huoshan_ai_agent`）、Redis（后端会话库 1；引擎的会话存 Redis，见下）
**JDK：服务器要同时装 17 与 21** —— 后端是 Java 17，**引擎与 MCP 是 Java 21**（两个 pom 里 `<java.version>21</java.version>`）。用 17 去起引擎/MCP 会直接报不支持的 class 版本；起这两个进程时必须显式指到 21 的 `java`（2026-09-16 补记，此前清单没写）。
```

**引擎与 MCP 都只监听回环**（D1，已写进入库的 `application.yaml`，prod 自动生效，**不用在 prod 配置里再写**）。前提是三者同机；分机部署则需改用防火墙/安全组只放 8123 出去。

## 2. 后端（`backend`）

已有部署不变，一期新增两项：

| 键 | 必需 | 说明 |
|---|---|---|
| `AI_ENGINE_BASE_URL` | 是 | 引擎地址，默认 `http://localhost:8124/api` |
| `AI_ENGINE_INTERNAL_API_KEY` | 是 | 服务间密钥，**为空即 fail-closed（代理一律拒绝）**；**必须与引擎的 `HUOSHAN_HEADLESS_API_KEY` 完全同值** |
| `AI_VISION_BASE_URL` / `AI_VISION_API_KEY` / `AI_VISION_MODEL` | AI 打标要 | 三个缺一即视为未配置（打标接口直接回"模型未配置"，不发注定失败的请求） |
| `DB_PASSWORD` / `MAIL_*` | 已有 | 沿用现有 prod 配置 |

## 3. 引擎（`ai/agent`，新进程）

必需项（这些都是**入库配置里的占位符**，除 `thinking` 外都能用环境变量给）：

| 键 | 必需 | 说明 |
|---|---|---|
| `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 是 | 默认 `jdbc:mysql://localhost:3306/huoshan_ai_agent`；用户名必须填写服务器实际创建的 MySQL 用户（本次宝塔真机为 `huoshan_ai_agent`，不能照抄旧示例 `huoshan_agent`）。**引擎不使用 Flyway，空库不会自建表**：先建库再执行 `deploy/sql/engine-schema.sql`（见第 5 节第 1 步）。口径与理由见 `docs/decisions/2026-09-16-flyway-removed.md` |
| `HUOSHAN_BASE_URL` | 是 | 图库后端地址，默认 `http://localhost:8123/api` |
| `HUOSHAN_HEADLESS_API_KEY` | 是 | **与后端 `AI_ENGINE_INTERNAL_API_KEY` 同值** |
| `AI_OPENAI_BASE_URL` / `AI_OPENAI_API_KEY` / `AI_OPENAI_MODEL` | 是 | 主脑（当前 MiMo `mimo-v2.5`，base-url 不带尾部 `/v1`） |
| `AI_OPENAI_VISION_BASE_URL` / `_API_KEY` / `_MODEL` | 看图打标要 | 视觉模型（MiMo 只有 `mimo-v2.5` 支持图片） |
| `--app.ai.openai.assistant.extra-body.thinking.type=disabled`<br>`--app.ai.openai.vision.extra-body.thinking.type=disabled` | **是（陷阱 2）** | 入库配置里没有，必须显式给 |
| `AI_MCP_IMAGE_SEARCH_URL` | 部署 MCP 时 | 默认 `http://localhost:8127` |
| `AI_MCP_CLIENT_ENABLED` | 见右 | **不部署 MCP 就设 `false`**。不设且 MCP 不可达也能用（失败即降级 + 60s 冷却），但每个故障窗口的**第一次对话要白等约 20s** |
| Redis（`spring.data.redis.host/port` + `spring.session.store-type` / `spring.session.redis.namespace`） | 视需要 | **只配在不入库的 yaml 里**，入库配置没有这组。引擎自身的会话（`HUOSHAN_AI_SESSION` cookie，供其自带前端）用它；助手链路本身不依赖会话 |
| ~~`AI_DASHSCOPE_API_KEY`~~ | **不再需要**（2026-09-16 收口） | 原先必须给一个非空值（`spring.ai.model.chat: dashscope` 会急切建 Bean，缺 key 就起不来）。现在引擎侧**没有任何 DashScope 用法**——对话主脑、看图打标、会话摘要、会话标题全走 `app.ai.openai.*`（MiMo），入库配置里已把 `model.chat` / `model.embedding` 置 `none` 并整体 `dashscope.enabled: false`。**实测三种无 key 形态都能起**（见 `docs/decisions/2026-09-16-dashscope-decoupling.md`）。**注意**：`dashscope.enabled: false` 不能省——`model.chat` 只管得到 chat 那条自动配置，DashScope 的 agent 那条（`dashScopeAgent`）缺 key 时照样让启动失败 |
| `VECTOR_ENABLED=false` | 是 | 向量库已停用，默认就是 false，别打开 |

启动：`java -Xms128m -Xmx512m -jar my-ai-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod`（JVM 参数必须在 `-jar` 前；或全部走环境变量 + 显式覆盖 profile）。

## 4. MCP（`ai/image-search-mcp-server`，新进程）

| 键 | 必需 | 说明 |
|---|---|---|
| `PEXELS_API_KEY` | 是 | 服务自己打 Pexels |
| `--spring.profiles.active=sse` | 是 | 入库 `application.yaml` 默认已是 `sse` |
| 端口 8127 + 回环 | — | 已入库（D1），无需另配 |

## 5. 上线顺序与自检

1. 建库与建表：`CREATE DATABASE \`huoshan_ai_agent\` CHARACTER SET utf8mb4;`，然后在目标库里执行建表脚本 —— **引擎不使用 Flyway，这一步不能跳**（空库直接起引擎会在第一次读写时报表不存在）：
   `mysql -u root -p --default-character-set=utf8mb4 huoshan_ai_agent < deploy/sql/engine-schema.sql`
   脚本幂等（`create table if not exists`，可重复执行），自带 `set names utf8mb4`；**`--default-character-set=utf8mb4` 别省**——中文 Windows 上客户端默认字符集不是 utf8mb4，会在带中文默认值的列上报 `ERROR 1067`
2. 起 MCP（8127）→ 看启动日志无异常
3. 起引擎（8124）→ 日志里应出现 `OpenAI 协议模型就绪：config=app.ai.openai.assistant, baseUrl=..., model=mimo-v2.5 ... extraBodyKeys=[thinking]`（**`extraBodyKeys=[thinking]` 就是陷阱 2 的验收点**）
   - **引擎起不来时先看这两条**（2026-09-16 实测过，但都已**不再是默认形态的坑**）：`DashScope API key must be set` → 说明有人把入库配置的 `dashscope.enabled: false` 覆盖回 true 了；`Parameter 1 of constructor in FlowWindowBasedChatMemory required a bean of type ChatModel` → 说明 `spring.ai.model.chat` 被从 `none` 改回去了。**这两条都指向"默认配置被动过"，不要靠补一个 key 去绕**
4. 起后端（8123，**显式 profile**）→ 日志无 fail-closed 警告
5. 合并 nginx 片段 → `nginx -t` → `reload`
6. 逐项自检：

- [ ] `netstat` 看 8124 / 8127 **只绑 127.0.0.1**（不是 `0.0.0.0`）
- [x] 后端 → 引擎联通：登录后打开 `/assistant` 发一句话，能出步骤条与回答（2026-09-17 真机通过）
- [ ] **SSE 逐步显示**：折叠条是**一步步**出现，不是最后一次性刷出（D3 的验收点）
- [x] 登录态透传：助手能列出**你自己的**空间（不是别人的）（2026-09-17 真机通过）
- [ ] **摘要走主脑**：让一条对话够长（历史估算 token 过 4096，通常要几轮长回答），引擎日志出现 `摘要模型调用完成`；**不应**出现 `会话摘要压缩失败，降级为硬裁剪`（出现即说明主脑没配全，摘要悄悄退化成"丢掉老消息"）
- [ ] 引擎日志里 `调用者非管理员，本次会话不挂 visionTagger` 只在普通用户会话出现
- [ ] MCP 通：问"找几张 X 的图"能搜到（`searchImage` 被调用）
- [ ] **助手只读边界**：问“把这些图放进空间”时不得调用 `batchUploadByUrl`、不得声称已入库，只返回候选或引导到普通图库页面
- [ ] **助手只读边界**：问“直接改这些图片的标签”时不得调用 `batchEditPictures`、不得写库，只给建议或引导到普通图库页面
- [ ] **日志脱敏**：完成一轮查询与搜图后，agent INFO 日志中不出现完整用户 Prompt、工具参数或工具返回正文
- [x] AI 打标（管理端公共图库「AI 打标」）能出建议 → 确认入库（2026-09-17 用户确认可用）
- [x] **部署后把 `docs/spec.md` 的 F11 勾上**（2026-09-17 已完成）
- [x] 后端同步升级，A1 的旧版匿名枚举暴露随部署自然收口（2026-09-17）

## 6. 明确未覆盖 / 仍开着的

**首次部署的两项物料结果**：

1. **已解决：仓库已 push，且首次部署选择本地打包后上传服务器。** 2026-09-17 部署前 `main` 与 `origin/main` 均指向 `10571ea`；服务器运行产物未做哈希回读，线上版本以该部署候选记录。
2. **已解决：生产空库已执行 `deploy/sql/engine-schema.sql` 并确认 4 张表存在。** 首轮助手请求暴露的是 MySQL 用户名不一致，而非缺表；改为宝塔实际用户 `huoshan_ai_agent` 后对话成功。

**其余未覆盖 / 仍开着的**：

- **常驻方式已选宝塔 Java 项目管理**（2026-09-17）：backend / agent / MCP 均由面板托管。开机自启、崩溃重拉与日志轮转的具体开关没有留下截图或文本输出，服务器重启后仍应做一次恢复演练。
- **前端构建命令与发布落点已确认**（2026-09-17）：静态根目录为 `/www/wwwroot/Java/yun-picture-frontend`；本次上线使用 `cd frontend && npm ci && npm run pure-build`，不使用会被 124 例存量 type-check 基线阻断的 `npm run build`。`pure-build` 已在当前候选代码上实跑成功（4015 modules，约 32.5s）；产物为 `frontend/dist/`，服务器静态根目录已确认；后续替换前仍应备份旧目录。当前主 bundle 约 2.95 MB（gzip 约 941 KB）会触发 Vite 大 chunk 警告，但不阻断本次发布。
- **D2 引擎 `CorsConfig`（`allowCredentials(true)` + 通配 Origin）缓解未修**：回环绑定后远程浏览器够不到，但**本机进程仍可利用**。不算已修好。
- **SSE 中继的三个已知边界**（按规则 13 判为已知限制，不挂账）：上游多行 `data:` 的半帧、上游缺 `[DONE]` 时答案后多一句"响应意外中断"、线程池拒绝只给空体 500。详见 `docs/features/F11-AI助手MVP.md`「已知限制」9。
- **`/ticket` 无频控、`message`/`chatId` 无长度校验**（同上，已知限制 7 ⑧）。
- **真机验证范围有限**：用户已完成部署、修复数据库用户名并确认助手与 AI 打标可用，也已合并 nginx；但没有保存 `netstat`、`nginx -t`、长对话摘要、MCP 搜图、只读负向控制和日志脱敏的文本输出。未勾选项仍需后续按需复测。
