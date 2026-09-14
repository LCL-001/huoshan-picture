# F11 AI 助手（档 2 用户可见 MVP）

> 覆盖任务：docs/plan.md T10（图库后端 AI 代理模块）+ T11（前端助手界面）；档 2 是本轮"能用"的终点。
> 引擎侧（会话类型 / headless 端点 / 三个只读工具）见档 1 计划文档；本文件只讲**用户能看见的这条链**。

## 一句话

登录后在 `/assistant` 页面与内嵌 AI 助手对话：助手**用你自己的登录态**只读地查你的空间、图片与标签词表，执行步骤以折叠条逐步显示，回答进气泡。

## 怎么用

1. 顶栏菜单或左侧浮动栏点「AI 助手」（入口在任何页面都在；移动端在汉堡抽屉里）。
2. 空状态给了三个示例问题，点一下即填入输入框；`Enter` 发送、`Shift+Enter` 换行。
3. 发送后：你自己的消息靠右、下方出现**默认展开的步骤折叠条**（`思考` 与 `工具 · 工具名` 两类，工具步骤里显示引擎返回的结构化 JSON），助手回答靠左。输入框在回答期间禁用，按钮变成红色「停止」。
4. 「停止」= 关闭这条 SSE 流（不是暂停）；「新对话」= 换一条对话串（旧会话记忆仍留在引擎，但不再续聊）。
5. 未登录点进来会被重定向到登录页（`EventSource` 读不到 HTTP 状态码，登录态必须前置判）。

## 怎么实现

### 关键文件

| 层 | 文件 | 职责 |
|---|---|---|
| 前端页面 | `frontend/src/pages/AssistantPage.vue` | 对话面板：消息列表、输入区、停止/新对话、登录前置判 |
| 前端组件 | `frontend/src/components/assistant/StepTimeline.vue` | SSE 步骤折叠条（`a-collapse`，运行中显示转圈） |
| 前端 SSE | `frontend/src/utils/assistantSse.ts` | `EventSource` 封装，解析 `step`/`answer`/`[DONE]` |
| 前端地址 | `frontend/src/api/assistantController.ts` | 拼 GET 流地址（复用 `request.ts` 导出的 `BASE_URL`） |
| 后端端点 | `backend/.../controller/AiAssistantController.java` | 登录门槛、凭据组取值、`GET /api/ai/assistant/chat` |
| 后端转发 | `backend/.../manager/ai/AiAssistantProxyManager.java` | 打引擎、逐帧中继 SSE、失败合成 answer、断流关上游 |
| 后端配置 | `backend/.../config/AiAssistantProperties.java` + `application.yaml` 的 `app.ai.assistant.*` | 引擎地址、服务间密钥、两个超时 |
| 后端线程池 | `backend/.../config/ThreadPoolConfig.java` 的 `aiAssistantExecutor` | SSE 转发专用有界守护线程池（上界 64，不排队） |

### 核心流程

```
浏览器 EventSource(credentials)  →  GET /api/ai/assistant/chat?message=&chatId=
   ↓ AiAssistantController：getLoginUser（Spring Session 登录态）
   ↓ 从浏览器请求取一组凭据：satoken（头→Cookie→query）+ SESSION 会话 Cookie（原样值）
   ↓ AiAssistantProxyManager（专用线程）：HttpURLConnection GET 引擎
        {engine}/api/ai/huoshan/chat?message=&userId=&chatId=
        头：X-Internal-Api-Key（配置）、satoken（原样）、Accept: text/event-stream
        Cookie：SESSION=<浏览器原样值>
   ↓ 引擎：图库助手会话类型 → 三个只读工具（各自带这组凭据打图库 API，RBAC 由图库判）
   ↓ SSE 逐帧中继回浏览器（payload 原样，遇 [DONE] 收尾）
前端按 data 帧里的 event 字段分流：step→折叠条、answer→气泡、[DONE]→关流
```

### 关键设计与理由

1. **SSE 转发选型 = `SseEmitter` + JDK `HttpURLConnection` + 专用有界线程池**（设计文档留的"实施时定"）。理由：零新增依赖（Spring `WebClient` 要引 webflux；仓内 `okhttp-2.7.5` 只是 COS SDK 的传递依赖，2.x 太老不该直接用）。SSE 转发线程按一次对话时长占用（最长 300s），所以用独立线程池 + `SynchronousQueue` 直接拒绝，避免长任务把上传/异步线程池饿死。
2. **凭据组原样透传，且会话 Cookie 必须是浏览器带的那个值**。这是本档最贵的一课：首轮冒烟工具回 `40100 未登录`，根因是代理转发了 `session.getId()`——Spring Session 默认把会话 id 做 Base64 后写进 Cookie，`getId()` 是**解码后**的 id，两者不等价（实测：原样 Cookie 值 → 空间列表 `code=0`；解码值 → `40100`）。现固定为按 Cookie 名取值转发，并留了回归测试钉住。
3. **代理只判"存在性"，不判身份**。两把凭据在场即可转发，是否有权由图库服务端判定（decisions.md 2026-09-14 B 落地条：引擎不校验两把是否属同一用户，交图库判）。理由：授权判定只应有一处，代理复刻一套 Sa-Token 解析既会漂移口径、又可能误拒。
4. **上游错误与代理侧失败都合成一条 `answer` 事件 + `[DONE]`**。理由：`EventSource` 拿不到 HTTP 状态码与错误响应体（本仓 `GlobalExceptionHandler` 恒回 HTTP 200），不做这层前端只会看到"连接断开"；不新增 SSE 事件类型也就没动引擎契约。覆盖三类：引擎返回非 SSE（401/40000/50000 JSON，取其 `message`）、连接或读取超时与 IOException（"引擎暂时不可用"）、流意外中断。
5. **客户端断流即断开上游**。代理侧的上游（引擎）句柄在我们手里，`onCompletion/onError/onTimeout` 直接 `disconnect()`；这与 decisions.md 挂在档 2 之后的 R1（引擎侧 provider 连接因 `Flux.cache(0)` 取消不断开）不是同一回事。
6. **前端必须 `EventSource` + 显式 `close()`**。不能用 `request.ts` 的 axios（浏览器端无流式响应形态，且 10s 超时会掐断长对话）；而 `EventSource` 在流正常结束与出错时**都会自动重连**，不 `close()` 就会把同一条 message 重发一遍（等于重跑一次 agent、重复调工具）。
7. **`/ai/**` 不进 `SaTokenConfigure` 的拦截路径**，登录门槛在 controller 里显式 `getLoginUser`。理由：Sa-Token 注解只在注册路径上生效，未注册路径上的 `@SaCheckLogin` 会**静默失效**（比不写更危险）。
8. **凭据不落库、不进日志**：`log.info` 只记 `userId` 与 `chatId`；密钥走环境变量 / `application-local.yaml`，不入库。

## 怎么验证

- **门禁（不依赖 MySQL/Redis）**：`backend` 32 例全绿，其中本档新增 19 例——`AiAssistantControllerTest` 10 例（登录门槛、satoken 三处取值与优先级、会话 Cookie 缺失拒绝、**会话 Cookie 原样值 vs `session.getId()` 回归**）、`AiAssistantProxyManagerTest` 9 例（转发形状与凭据头、SSE 逐帧中继与格式容错、引擎 401/JSON 错误与不可达转可见文案、密钥/地址缺失 fail-closed 且零上游请求、线程池满响亮失败）。
- **集成测试（本地，需 MySQL/Redis）**：`AiAssistantProxyIntegrationTest` 3 例——真实登录产出 `satoken` Cookie 后原样转发、会话 Cookie 原样值转发（不等于 `session.getId()`）、只带 satoken 不带会话 Cookie 在入口即拒且零上游请求。
- **curl 冒烟（真引擎 + 真图库）**：探针账号登录 → 走代理 → `listSpaces` 读到真实空间 `tier1-probe-space`、回答正确、`[DONE]` 收尾；停掉引擎再打一次 → 收到合成的"引擎暂时不可用"+`[DONE]`（不挂死）。原始片段见 handoff 0013。
- **浏览器端到端**：本地起 backend(`local,test`)+引擎+前端 dev，登录后进 `/assistant`，发送后界面依次呈现：用户气泡 → 折叠条「正在执行（0 步）」+ 转圈 + 输入禁用 + 停止按钮 → 「执行步骤（1 步）」含 `工具 · listSpaces` 与真实返回 → 回答气泡 → 输入恢复。该会话用户真实空间数为 0，工具仍回 `code=0`，说明凭据确实透传成功（未透传会是 `40100`）。
- 前端 `npm run type-check`：**净增 0**（基线 138 例既有错误，见已知限制）。

## 已知限制

1. **回答按纯文本渲染**，模型若输出 Markdown（实测回答里出现 `**加粗**`）会字面显示星号。要修就得加 markdown 渲染依赖或自己写极简渲染，属额外范围。
2. **错误没有独立视觉**：服务端失败以"助手回答"的形式出现（如"图库助手引擎暂时不可用，请稍后重试"），看起来像模型在说话；也没有独立的错误事件类型（引擎侧"超时转显式错误事件"仍按 decisions.md 挂账）。
3. **对话历史不落库**：前端内存态，刷新即丢；`chatId` 存 `sessionStorage`，刷新仍续同一条对话串。引擎侧多轮记忆由 `chat_message` 承载（档 1 口径）。
4. **同一页面只允许一条流**（发送中禁输入）；"停止"只关流，不承诺让引擎侧停止（R1）。
5. **前端门禁存量红**：`npm run type-check` 138 例、`eslint .` 73 例既有错误（分布在本次未触碰的文件里，多为 `LocationQueryValue`/`any`/未用变量一类），AGENTS/handoff 0001 里"前端以 type-check + lint 为准"这条目前**不成立**；本次改动净增 0，清理需单独立任务。
6. 单机部署口径：引擎与图库同机/内网（`app.ai.assistant.engine-base-url` 默认 `http://localhost:8124/api`），引擎未上公网；`/ai/manus/chat` 旧端点仍未鉴权（部署前必办，档 1 挂账）。
