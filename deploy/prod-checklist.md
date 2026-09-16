# AI 助手一期 —— 生产部署清单（D4）

> 2026-09-15 立。目标：把一期从"代码完成"推到"线上可用"，并把踩坑点写在前头。
> 口径见 `docs/decisions/2026-09-15-D1-loopback.md`；SSE 的 nginx 片段见 `deploy/nginx/ai-assistant-sse.conf`。

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
依赖：MySQL（后端 `yu_picture` + 引擎 `yu-ai-agent`）、Redis（后端会话库 1；引擎的会话存 Redis，见下）
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
| `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 是 | 默认 `jdbc:mysql://localhost:3306/yu-ai-agent`。**库建好空库即可**：入库配置里 Flyway 是开的，启动时会按 `db/migration/V1–V4` 自建表结构 |
| `HUOSHAN_BASE_URL` | 是 | 图库后端地址，默认 `http://localhost:8123/api` |
| `HUOSHAN_HEADLESS_API_KEY` | 是 | **与后端 `AI_ENGINE_INTERNAL_API_KEY` 同值** |
| `AI_OPENAI_BASE_URL` / `AI_OPENAI_API_KEY` / `AI_OPENAI_MODEL` | 是 | 主脑（当前 MiMo `mimo-v2.5`，base-url 不带尾部 `/v1`） |
| `AI_OPENAI_VISION_BASE_URL` / `_API_KEY` / `_MODEL` | 看图打标要 | 视觉模型（MiMo 只有 `mimo-v2.5` 支持图片） |
| `--app.ai.openai.assistant.extra-body.thinking.type=disabled`<br>`--app.ai.openai.vision.extra-body.thinking.type=disabled` | **是（陷阱 2）** | 入库配置里没有，必须显式给 |
| `AI_MCP_IMAGE_SEARCH_URL` | 部署 MCP 时 | 默认 `http://localhost:8127` |
| `AI_MCP_CLIENT_ENABLED` | 见右 | **不部署 MCP 就设 `false`**。不设且 MCP 不可达也能用（失败即降级 + 60s 冷却），但每个故障窗口的**第一次对话要白等约 20s** |
| Redis（`spring.data.redis.host/port` + `spring.session.store-type` / `spring.session.redis.namespace`） | 视需要 | **只配在不入库的 yaml 里**，入库配置没有这组。引擎自身的会话（`YUAI_SESSION` cookie，供其自带前端）用它；助手链路本身不依赖会话 |
| `AI_DASHSCOPE_API_KEY` | **是（只要非空）** | 入库配置里 `spring.ai.model.chat: dashscope` 是**默认值**，它会**急切创建** `dashScopeChatModel` Bean。**实测两种做法都起不来**：① key 为空 → `DashScope API key must be set`；② 把 `spring.ai.model.chat` 置 `none` 想绕开 → `Parameter 1 of constructor in FlowWindowBasedChatMemory required a bean of type ChatModel that could not be found`。**但值不必真实可用**：2026-09-16 起**摘要压缩已改走主脑（MiMo）**，助手链路（对话 / 看图打标 / 摘要）都不再碰它，随便给个非空占位即可。**仍会真的用到它的只剩冻结的旧链路**：`/ai/manus/chat`（MyManus，零鉴权 + 只监听回环）与它顺带触发的会话标题生成 `ai/agent/src/main/java/com/lcl/myaiagent/service/ConversationTitleService.java`——给假 key 时这两处调用会报错（标题那处已 try/catch，只记 warn，不影响对话）。要把 DashScope 整个摘掉得让这两处也能缺省，属独立改动 |
| `VECTOR_ENABLED=false` | 是 | 向量库已停用，默认就是 false，别打开 |

启动：`java -jar my-ai-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod`（或全部走环境变量 + 显式覆盖 profile）。

## 4. MCP（`ai/image-search-mcp-server`，新进程）

| 键 | 必需 | 说明 |
|---|---|---|
| `PEXELS_API_KEY` | 是 | 服务自己打 Pexels |
| `--spring.profiles.active=sse` | 是 | 入库 `application.yaml` 默认已是 `sse` |
| 端口 8127 + 回环 | — | 已入库（D1），无需另配 |

## 5. 上线顺序与自检

1. 建库：`CREATE DATABASE \`yu-ai-agent\` CHARACTER SET utf8mb4;`
2. 起 MCP（8127）→ 看启动日志无异常
3. 起引擎（8124）→ 日志里应出现 `OpenAI 协议模型就绪：config=app.ai.openai.assistant, baseUrl=..., model=mimo-v2.5 ... extraBodyKeys=[thinking]`（**`extraBodyKeys=[thinking]` 就是陷阱 2 的验收点**）
   - **引擎起不来时先看这两条**（2026-09-16 实测都是真实故障形态）：`DashScope API key must be set`（缺 `AI_DASHSCOPE_API_KEY`）；`Parameter 1 of constructor in FlowWindowBasedChatMemory required a bean of type ChatModel`（把 chat 模型关成 `none` 了）。两者都指向同一件事：**`AI_DASHSCOPE_API_KEY` 必须给个非空值**——占位值就行，摘要压缩自 2026-09-16 起走主脑（`docs/decisions/2026-09-16-summary-model.md`）
4. 起后端（8123，**显式 profile**）→ 日志无 fail-closed 警告
5. 合并 nginx 片段 → `nginx -t` → `reload`
6. 逐项自检：

- [ ] `netstat` 看 8124 / 8127 **只绑 127.0.0.1**（不是 `0.0.0.0`）
- [ ] 后端 → 引擎联通：登录后打开 `/assistant` 发一句话，能出步骤条与回答
- [ ] **SSE 逐步显示**：折叠条是**一步步**出现，不是最后一次性刷出（D3 的验收点）
- [ ] 登录态透传：助手能列出**你自己的**空间（不是别人的）
- [ ] **摘要走主脑**：让一条对话够长（历史估算 token 过 4096，通常要几轮长回答），引擎日志出现 `摘要模型调用完成, 模型来源: 图库助手主脑`；若打的是 `模型来源: 容器默认模型`，说明 `AI_OPENAI_*` 三件套没配全，摘要退回了 DashScope
- [ ] 引擎日志里 `调用者非管理员，本次会话不挂 visionTagger` 只在普通用户会话出现
- [ ] MCP 通：问"找几张 X 的图"能搜到（`searchImage` 被调用）
- [ ] AI 打标（管理端公共图库「AI 打标」）能出建议 → 确认入库
- [ ] **部署后把 `docs/spec.md` 的 F11 勾上**（D5：用户拍板"等一期部署上线后再勾"）
- [ ] 顺手把后端也升上去——线上目前跑的还是修复前的版本（`plan.md`「已接受的暴露」A1：匿名可枚举空间与属主）

## 6. 明确未覆盖 / 仍开着的

**先解决两件"物料怎么到服务器"的事**（都不是技术难点，但会直接卡住第一次部署）：

1. **仓库还没 push**。`origin/main` 停在 2026-09-09，本地 `main` 领先 **205 笔**（含本次 AI 助手全部改动与安全修复）。**若部署路径是"在服务器上 clone/pull 再打包"，拿到的是两个月前的旧代码。** 两条路选一条：push（仓库是公开的，注意第 0 节陷阱 1 说的"文档里写着线上仍未修"这层含义）／或直接 scp 构建产物（那就必须遵守陷阱 1 的 profile 纪律）。
2. **引擎从未以 prod 形态起过**。它在本机跑了很多次，但每次都是连着**本机那个已有数据的库**（`yu-ai-agent`，Flyway 的 V1–V4 是几个月前就跑过的）。**"空库首跑 Flyway 建表 → 引擎正常启动 → 助手能对话"这条完整路径没验过。**

**其余未覆盖 / 仍开着的**：

- **D2 引擎 `CorsConfig`（`allowCredentials(true)` + 通配 Origin）缓解未修**：回环绑定后远程浏览器够不到，但**本机进程仍可利用**。不算已修好。
- **SSE 中继的三个已知边界**（按规则 13 判为已知限制，不挂账）：上游多行 `data:` 的半帧、上游缺 `[DONE]` 时答案后多一句"响应意外中断"、线程池拒绝只给空体 500。详见 `docs/features/F11-AI助手MVP.md`「已知限制」9。
- **`/ticket` 无频控、`message`/`chatId` 无长度校验**（同上，已知限制 7 ⑧）。
- **本清单与 nginx 片段都未经真机验证**：我没有服务器访问权。第 5 节的命令、`netstat` 期望值、第 3 步的启动日志断言都已尽量写成可机械核对的形态，但仍以你实际跑出来的为准；**若第 5 节任一步的现象与期望不符，先停下对照本节排查，别硬往下走**。
