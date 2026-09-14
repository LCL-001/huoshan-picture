# F11 AI 助手（档 2 用户可见 MVP）

> 覆盖任务：docs/plan.md T10（图库后端 AI 代理模块）+ T11（前端助手界面）；档 2 是本轮"能用"的终点。
> 引擎侧（会话类型 / headless 端点 / 三个只读工具）见档 1 计划文档；本文件只讲**用户能看见的这条链**。
> 2026-09-14 追加：档 2 独立 review 的 **R4（代理侧身份一致性校验）** 与 **R6（Sa-Token 注解作用域守护测试）**
> 已按用户拍板收口，见「关键设计与理由」3/7 与「已知限制」8。

## 一句话

登录后在 `/assistant` 页面与内嵌 AI 助手对话：助手**用你自己的登录态**只读地查你的空间、图片与标签词表，执行步骤以折叠条逐步显示，回答进气泡。

## 怎么用

1. 顶栏菜单或左侧浮动栏点「AI 助手」（入口在任何页面都在；移动端在汉堡抽屉里）。
2. 空状态给了三个示例问题，点一下即填入输入框；`Enter` 发送、`Shift+Enter` 换行。
3. 发送后：你自己的消息靠右、下方出现**默认展开的步骤折叠条**。折叠条里两类行：`思考`（模型的自然语言推理）与 `工具 · <中文别名>`——工具行默认只给一句话摘要（如「共 3 个空间」「标签 13 个 · 分类 5 个」「失败：未登录」），原始返回点该行的「详情」才展开。助手回答靠左，按 Markdown 子集渲染（粗体、列表、行内代码、链接、引用）。输入框在回答期间禁用，按钮变成红色「停止」。
4. 「停止」= 关闭这条 SSE 流（不是暂停）；「新对话」= 换一条对话串（旧会话记忆仍留在引擎，但不再续聊）。
5. 未登录点进来会被重定向到登录页（`EventSource` 读不到 HTTP 状态码，登录态必须前置判）。

## 怎么实现

### 关键文件

| 层 | 文件 | 职责 |
|---|---|---|
| 前端页面 | `frontend/src/pages/AssistantPage.vue` | 对话面板：消息列表、输入区、停止/新对话、登录前置判 |
| 前端组件 | `frontend/src/components/assistant/StepTimeline.vue` | SSE 步骤折叠条（`a-collapse`，运行中显示转圈） |
| 前端组件 | `frontend/src/components/assistant/ToolStepRow.vue` | 工具步骤行：中文别名 + 一句话摘要 + 「详情」折叠原始返回 |
| 前端组件 | `frontend/src/components/assistant/AssistantText.vue` + `AssistantInline.vue` | 回答/思考文本的 Markdown 子集渲染（块 + 行内两段） |
| 前端文本 | `frontend/src/utils/assistantFormat.ts` | 纯函数：Markdown 子集解析、工具返回摘要与中文别名 |
| 前端 SSE | `frontend/src/utils/assistantSse.ts` | `EventSource` 封装，解析 `step`/`answer`/`[DONE]` |
| 前端地址 | `frontend/src/api/assistantController.ts` | 拼 GET 流地址（复用 `request.ts` 导出的 `BASE_URL`） |
| 后端端点 | `backend/.../controller/AiAssistantController.java` | 登录门槛、凭据组取值、`GET /api/ai/assistant/chat` |
| 后端转发 | `backend/.../manager/ai/AiAssistantProxyManager.java` | 打引擎、逐帧中继 SSE、失败合成 answer、断流关上游 |
| 后端配置 | `backend/.../config/AiAssistantProperties.java` + `application.yaml` 的 `app.ai.assistant.*` | 引擎地址、服务间密钥、两个超时 |
| 后端线程池 | `backend/.../config/ThreadPoolConfig.java` 的 `aiAssistantExecutor` | SSE 转发专用有界守护线程池（上界 64，不排队） |

### 核心流程

```
浏览器 EventSource(credentials)  →  GET /api/ai/assistant/chat?message=&chatId=
   ↓ AiAssistantController 三道门槛：① getLoginUser（Spring Session 登录态）
        ② 一组凭据齐备：satoken（头→Cookie→query）+ SESSION 会话 Cookie（原样值）
        ③ 两把同属一人：StpKit.SPACE.getLoginIdByToken(satoken) == 会话用户 id
          （不满足即拒：无效/过期 40100、不属于同一人 40102；零上游请求）
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
3. **代理判"两把同属一人"，但不判权限**（2026-09-14 review R4 后改口径，此前是"只判存在性"）。三道门槛：Spring Session 登录态 → 凭据组两把在场 → `StpKit.SPACE.getLoginIdByToken(satoken)` 与会话用户同 id（无效/过期 40100，有效但不同人 40102，与 `checkSpaceViewPermission` 同口径、零上游请求）。理由：代理的职责边界是"透传出去的凭据属于谁"——`listSpaces` 一类路径只由 Spring Session 授权，不补这一刀就等于放行任意非空白 satoken（review 实测：真会话 + 假 token `review-bogus-token` 仍让引擎读到真实空间）；而"能不能看某个空间"仍只由图库服务端判，代理不复制权限逻辑，避免口径漂移与误拒。
4. **上游错误与代理侧失败都合成一条 `answer` 事件 + `[DONE]`**。理由：`EventSource` 拿不到 HTTP 状态码与错误响应体（本仓 `GlobalExceptionHandler` 恒回 HTTP 200），不做这层前端只会看到"连接断开"；不新增 SSE 事件类型也就没动引擎契约。覆盖三类：引擎返回非 SSE（401/40000/50000 JSON，取其 `message`）、连接或读取超时与 IOException（"引擎暂时不可用"）、流意外中断。
5. **客户端断流即断开上游**。代理侧的上游（引擎）句柄在我们手里，`onCompletion/onError/onTimeout` 直接 `disconnect()`；这与 decisions.md 挂在档 2 之后的 R1（引擎侧 provider 连接因 `Flux.cache(0)` 取消不断开）不是同一回事。
6. **前端必须 `EventSource` + 显式 `close()`**。不能用 `request.ts` 的 axios（浏览器端无流式响应形态，且 10s 超时会掐断长对话）；而 `EventSource` 在流正常结束与出错时**都会自动重连**，不 `close()` 就会把同一条 message 重发一遍（等于重跑一次 agent、重复调工具）。
7. **`/ai/**` 不进 `SaTokenConfigure` 的拦截路径**，登录门槛在 controller 里显式 `getLoginUser`。理由：Sa-Token 注解只在注册路径上生效，未注册路径上的 `@SaCheckLogin` 会**静默失效**（比不写更危险）。这条不变量现由 `AiPathSaTokenGuardTest` 反射守护（2026-09-14 R6）：① `/ai/**` 下的控制器不得出现任何 Sa-Token 注解；② 全仓凡用了 Sa-Token 注解的控制器，其路径必须落在 `SaTokenConfigure.ANNOTATION_GUARDED_PATTERNS` 覆盖范围内（把"注解写在没注册的路径上"变成响亮的红）；③ 拦截路径清单不得包含 `/ai/**`（前提变了要连带复核口径）。
8. **凭据不落库、不进日志**：`log.info` 只记 `userId` 与 `chatId`；密钥走环境变量 / `application-local.yaml`，不入库。
9. **呈现层两条口径（T11.1，用户实机反馈后定）**：① 回答渲染 **Markdown 子集**——自写解析（`**粗体**`／`` `代码` ``／http(s) 链接／`- `·`1. ` 列表／`> ` 引用）+ Vue 模板插值，**不引 markdown 库、绝不用 `v-html`**：回答内容会被工具数据与用户提问影响，只有"永不产出 HTML 字符串"才能从根上不留 XSS 面（引库则必须再引 sanitizer）。② 工具步骤**默认只给一句话摘要**（中文别名 + 「共 N 个空间 / 共 N 张图片 / 标签 N 个·分类 M 个 / 失败：原因」，由工具返回的 JSON 载荷算出），原始 JSON（图片 URL、雪花 id）收进「详情」、默认收起——那是开发排查用的，对普通用户是噪音。摘要解析失败一律退化为"已完成"，永不抛错或空屏。

## 怎么验证

- **门禁（不依赖 MySQL/Redis）**：`backend` 37 例全绿，其中本档 24 例——`AiAssistantControllerTest` 12 例（登录门槛、satoken 三处取值与优先级、会话 Cookie 缺失拒绝、**会话 Cookie 原样值 vs `session.getId()` 回归**、**R4 两条：假 token 40100 / 别人的真 token 40102 且零上游**）、`AiAssistantProxyManagerTest` 9 例（转发形状与凭据头、SSE 逐帧中继与格式容错、引擎 401/JSON 错误与不可达转可见文案、密钥/地址缺失 fail-closed 且零上游请求、线程池满响亮失败）、`AiPathSaTokenGuardTest` 3 例（R6 守护，见设计理由 7）。R4 的单测用 Sa-Token 内存 DAO 播种真 token（无 Spring 时 `SaManager` 缺省即 `SaTokenDaoDefaultImpl`），验的是真实的 `getLoginIdByToken` 语义而非替身。
- **集成测试（本地，需 MySQL/Redis）**：`AiAssistantProxyIntegrationTest` 5 例——真实登录产出 `satoken` Cookie 后原样转发、会话 Cookie 原样值转发（不等于 `session.getId()`）、只带 satoken 不带会话 Cookie 在入口即拒且零上游请求、**R4 两条：假 token 在入口即拒（真会话也救不了）、第二个真实账号的 satoken 配本账号会话回 40102 且零上游**。本地全量集成套件（14 个类 55 例）实跑绿。
- **curl 冒烟（真引擎 + 真图库）**：探针账号登录 → 走代理 → `listSpaces` 读到真实空间 `tier1-probe-space`、回答正确、`[DONE]` 收尾；停掉引擎再打一次 → 收到合成的"引擎暂时不可用"+`[DONE]`（不挂死）。原始片段见 handoff 0013。
- **浏览器端到端**：本地起 backend(`local,test`)+引擎+前端 dev，登录后进 `/assistant`，发送后界面依次呈现：用户气泡 → 折叠条「正在执行（0 步）」+ 转圈 + 输入禁用 + 停止按钮 → 「执行步骤（1 步）」含 `工具 · listSpaces` 与真实返回 → 回答气泡 → 输入恢复。该会话用户真实空间数为 0，工具仍回 `code=0`，说明凭据确实透传成功（未透传会是 `40100`）。
- **呈现层纯函数断言（19 条，仓库外脚本）**：项目无前端测试框架，用 esbuild 把真实 `utils/assistantFormat.ts` 转成 mjs 后在 node 里断言——载荷直接取自真机 SSE 原文与回答原文（含 13 个标签的 `<code>` 列表、URL 尾随中文句号、`3 > 2` 不被误判成引用）。脚本 `%TEMP%\t11-smoke\format-check.mjs`，19/19 通过；改动的前端文件 eslint 干净、`vue-tsc --build --force` 计数仍 138（净增 0）。
- 前端 `npm run type-check`：**净增 0**（基线 138 例既有错误，见已知限制）。

## 已知限制

1. **Markdown 只支持子集**（2026-09-14 T11.1 已从"纯文本"升级）：支持粗体、行内代码、http(s) 链接、`- `/`1. ` 列表、`> ` 引用；**不支持**表格、嵌套列表、图片、标题、单星号斜体——模型若用这些语法会字面显示。要全覆盖就得引 markdown 库 + sanitizer（见口径 9 的取舍）。
2. **错误没有独立视觉**：服务端失败以"助手回答"的形式出现（如"图库助手引擎暂时不可用，请稍后重试"），看起来像模型在说话；也没有独立的错误事件类型（引擎侧"超时转显式错误事件"仍按 decisions.md 挂账）。
3. **对话历史不落库**：前端内存态，刷新即丢；`chatId` 存 `sessionStorage`，刷新仍续同一条对话串。引擎侧多轮记忆由 `chat_message` 承载（档 1 口径）。
4. **同一页面只允许一条流**（发送中禁输入）；"停止"只关流，不承诺让引擎侧停止（R1）。
5. **前端门禁存量红**：`npm run type-check` 138 例、`eslint .` 73 例既有错误（分布在本次未触碰的文件里，多为 `LocationQueryValue`/`any`/未用变量一类），AGENTS/handoff 0001 里"前端以 type-check + lint 为准"这条目前**不成立**；本次改动净增 0，清理需单独立任务。
6. 单机部署口径：引擎与图库同机/内网（`app.ai.assistant.engine-base-url` 默认 `http://localhost:8124/api`），引擎未上公网；`/ai/manus/chat` 旧端点仍未鉴权（部署前必办，档 1 挂账）。
7. **无 CSRF 令牌（2026-09-14 独立 review 实测）**：`GET /api/ai/assistant/chat` 只认 Cookie、又有 LLM 副作用，没有 nonce/一次性令牌。跨站顶层导航（恶意页 / 诱导点击 / 302 跳转）会带上 `SESSION`+`satoken` 两把 Cookie 并**真的跑一次 agent**（实测：从 `127.0.0.1:8135` 导航到 `localhost:8131` 的代理端点，桩引擎收到带 Cookie 的完整请求）；同页 `fetch(mode:'no-cors', credentials:'include')` 子资源请求则被 SameSite=Lax 拦下（代理回 40100、零上游请求）。**当前危害有限**（三个工具全只读：替受害者烧 token + 往其会话记忆里塞一条提问，且无外泄通道——CORS 只放行白名单源、Lax 挡住子资源读），**档 3 上 `batchEditPictures` / `batchUploadByUrl` 即升级为跨站写操作（P1）**，届时必须先加防护（session 内一次性 nonce 走 URL、或 POST + fetch 流式）。
8. **凭据组一致性（review R4）已于 2026-09-14 收口**：代理现在校验 `satoken` 与 Spring Session 用户同属一人（无效/过期 40100、不同人 40102，与 `checkSpaceViewPermission` 同口径，零上游请求），"真会话 + 随手编的 token 仍读到真实空间"这个洞不再成立。**残留边界（仍未做，按需另立任务）**：① 代理只判身份一致性、**不判权限**，能不能看某个空间仍只由图库服务端判；② 该失败发生在返回 `SseEmitter` 之前，走 `GlobalExceptionHandler` 回 HTTP 200 + JSON，而前端 `EventSource` 读不到响应体 → 用户只会看到"助手连接中断，请重试"，实际得重新登录（与限制 2 同源，前端若要做"登录态失效→跳登录页"需另开任务）；③ satoken 走 HttpOnly + SameSite=Lax Cookie，本档未改其存储形态。
9. **SSE 中继的四个边界（review 用自建桩引擎实测）**：① 上游多行 `data:` 被中继用 `\n` 拼成**单帧**发出，线路上是半个 JSON 帧（`data:{"event":"answer",` + 裸换行 + `"content":"multi-line"}`），浏览器 `JSON.parse` 失败、前端**静默丢弃**（现引擎不会发多行 data，属潜伏缺陷）；② 上游未发 `[DONE]` 就 EOF 时，已发出的完整答案后面会**再跟一条"图库助手响应意外中断，请重试"**（答案 + 假错误同屏）；③ 上游非 JSON 错误体（部署形态里若有网关，如 nginx 502 的 HTML）被原文塞进回答气泡（实测 `<html>...502 Bad Gateway...`）；④ 线程池拒绝给浏览器的是**空体 HTTP 500**（`completeWithError` 未走 `GlobalExceptionHandler`），前端只能显示"助手连接中断，请重试"。
10. **`chatId` 未做净化，可换行注入后端日志**（review 实测：`chatId=inj%0AFORGED-LINE-MARKER` 在日志里伪造出独立一行）。`message` 不进日志、凭据不进日志（三个探针实例日志对真 token/会话值 0 命中，含异常与拒绝路径）。
11. **`ai/agent` 的门禁口径是 `.githooks` 的排除名单，不是 AGENTS 那条通用过滤命令**：AGENTS/handoff 记的 `-Dtest='!*IntegrationTest,!RedisStringTest,!YunPictureBaseApplicationTests'` 直接套到 `ai/agent` 会红（97 例 1 错误：`MyManusTest` 注入不存在的 `MyManus` bean，遗留教学测试）；`.githooks/pre-commit:54` 那条排除 10 个类之后才是 87 例绿。两者差异未记录在 AGENTS 的测试命令节，容易让下一个人把红当回归。
