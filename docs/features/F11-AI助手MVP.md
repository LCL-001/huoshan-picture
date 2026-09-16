# F11 AI 助手（档 2 用户可见 MVP）

> 覆盖任务：docs/plan.md T10（图库后端 AI 代理模块）+ T11（前端助手界面）+ T14（2026-09-15 能力口径回填）+ T25（2026-09-16 自然语言回答范围约束）+ T26（2026-09-16 页面提示与示例改写）；档 2 是本轮"能用"的终点。
> 引擎侧（会话类型 / headless 端点 / 工具集）见档 1 计划文档，**档 3 的写工具与搜图见 `F12-图库助手档3工具与联调.md`**（看图打标、批量改标签、按 URL 入库、MCP 搜图）；本文件只讲**用户能看见的这条链**。
> 2026-09-14 追加：档 2 独立 review 的 **R4（代理侧身份一致性校验）** 与 **R6（Sa-Token 注解作用域守护测试）**
> 已按用户拍板收口，见「关键设计与理由」3/7 与「已知限制」8。
> 2026-09-14 再追加：**R5（跨站 CSRF）** 按用户拍板"采用一次性 ticket"收口——对话端点新增必需参数 `ticket`，
> 见「关键设计与理由」10 与「已知限制」7。
> 2026-09-14 再追加：**R5 收口的独立 review（规则 13，只读会话）结论"有条件通过"**——R5 本体成立、无 P1；
> 1 条 P2（验收 ⑤ 口径）+ 6 条 P3（含 apex CORS 白名单——实测其前提不成立后由 P2 降为加固项、建议直接删）。
> 全文见 docs/decisions.md 末行；新增残留边界已并入本文件「已知限制」7/9。
> 2026-09-14 再追加：**那条 P2（验收 ⑤ 口径）已按用户拍板"补跑"收口，探针实测通过**——条件解除，R5 review
> 结论即"通过"。补跑证据见「怎么验证」的"跨站导航探针"条与「已知限制」7 的 ⑥（实测口径）。
> 2026-09-15 追加：**T14** 把本文件与 `/assistant` 页面文案的"只读"口径回填为档 3 的真实能力（可读可改、改动须用户
> 明确要求、不支持删除）——原口径是档 1/档 2 期的真实描述，档 3 放开写权限后没跟上，属用户可见的错误声明。
> 见「一句话」「怎么用」第 4 条、核心流程图与「已知限制」12。
> 2026-09-15 再追加：**T17** 按角色裁剪工具——看图打标（`visionTagger`）**只挂给管理员会话**，
> 代理新增 `X-User-Role` 透传（见「怎么用」第 4 条、核心流程图与「已知限制」13）。

## 一句话

登录后在 `/assistant` 页面与内嵌 AI 助手对话：助手只处理**火山图库、空间、图片、素材、标签、分类、整理、搜图入库及平台权限**相关事项，并**用你自己的登录态**查你的空间、图片与标签词表，也能按你的要求动手整理——看图给建议（**仅管理员**，见「已知限制」13）、批量改标签、把网上搜到的图按 URL 入库（写入消耗空间配额）。改动**只在用户明确要求时发生**，且**不支持删除**；执行步骤以折叠条逐步显示，回答进气泡。

## 怎么用

1. 顶栏菜单或左侧浮动栏点「AI 助手」（入口在任何页面都在；移动端在汉堡抽屉里）。
2. 空状态给了四个示例问题，点一下即填入输入框：`列出我的空间`、`看看某个空间里有哪些图片`、`帮我整理这些图片的标签`、`搜索一些图片并放入我的空间`；`Enter` 发送、`Shift+Enter` 换行。
3. 发送后：你自己的消息靠右、下方出现**默认展开的步骤折叠条**。折叠条里两类行：`思考`（模型的自然语言推理）与 `工具 · <中文别名>`——工具行默认只给一句话摘要（如「共 3 个空间」「标签 13 个 · 分类 5 个」「失败：未登录」），原始返回点该行的「详情」才展开。助手回答靠左，按 Markdown 子集渲染（粗体、列表、行内代码、链接、引用）。输入框在回答期间禁用，按钮变成红色「停止」。
4. **助手能改数据（档 3 起，2026-09-15 补记）**：你明确要求时它会批量改标签、把搜到的图按 URL 入库（**写入会消耗空间配额**）；看图打标是"先给建议、你说要才落库"。它**不支持删除**——任何"已删除"的说法都不成立，也不会替你删。要求说得不清楚时它会先问你，不要指望它猜。
   **2026-09-15 T17 起看图打标仅管理员可用**：普通用户的助手**没有**这个工具（与"公共图库打标是管理端管理员能力"对齐），你仍然可以让它列空间/图片、批量改标签、按 URL 入库；要看图给建议得用管理员账号，或走管理端的公共图库「AI 打标」入口（见 `F14-AI打标.md`）。
5. 「停止」= 关闭这条 SSE 流（不是暂停）；「新对话」= 换一条对话串（旧会话记忆仍留在引擎，但不再续聊）。
6. 未登录点进来会被重定向到登录页（`EventSource` 读不到 HTTP 状态码，登录态必须前置判）。若**登录态在页面里失效**（发送时才发现），会先收到提示并被带去登录页——因为建流前的那次取票走的是 axios，能读到 40100；而流本身是 `EventSource`，读不到错误响应体。
7. 每次发送前前端会先取一张**一次性凭据**（POST `/api/ai/assistant/ticket`，60 秒有效、用一次即作废），这一步用户无感。取票失败时回答气泡会直接显示「助手暂时不可用（未能取得本次对话凭据），请稍后重试」。
8. 助手不是通用聊天机器人。编程、旅游、百科、写作、翻译等**整条都与图库无关**的问题会统一回复「我只能协助处理火山图库中的空间、图片、标签、整理和搜图入库等事项。」；这类纯范围外请求不会调用 `listSpaces` 等图库工具。混合请求会处理其中合法的图库事项，并拒绝范围外部分。

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
| 前端地址 | `frontend/src/api/assistantController.ts` | 取一次性凭据（`fetchAssistantTicket`，POST）+ 拼 GET 流地址（复用 `request.ts` 导出的 `BASE_URL`） |
| 后端端点 | `backend/.../controller/AiAssistantController.java` | 四道门槛、凭据组取值、`GET /api/ai/assistant/chat` 与签发端点 `POST /api/ai/assistant/ticket` |
| 后端凭据 | `backend/.../manager/ai/AiAssistantTicketManager.java` | 一次性凭据的签发与"取用即删"（Redis + Lua，单次使用、60 秒过期） |
| 后端转发 | `backend/.../manager/ai/AiAssistantProxyManager.java` | 打引擎、逐帧中继 SSE、失败合成 answer、断流关上游、透传调用者角色（`X-User-Role`，T17） |
| 后端配置 | `backend/.../config/AiAssistantProperties.java` + `application.yaml` 的 `app.ai.assistant.*` | 引擎地址、服务间密钥、两个超时 |
| 后端线程池 | `backend/.../config/ThreadPoolConfig.java` 的 `aiAssistantExecutor` | SSE 转发专用有界守护线程池（上界 64，不排队） |
| 引擎范围门卫 | `ai/agent/.../agent/HuoshanAssistantAgent.java` | 正式 ReAct 前做无工具范围分类；范围外固定拒绝且不进入工具链 |

### 核心流程

```
浏览器 axios(POST)  →  /api/ai/assistant/ticket  →  取一张一次性凭据（60s、取用即删）
浏览器 EventSource(credentials)  →  GET /api/ai/assistant/chat?message=&chatId=&ticket=
   ↓ AiAssistantController 四道门槛：① getLoginUser（Spring Session 登录态）
        ② 一组凭据齐备：satoken（头→Cookie→query）+ SESSION 会话 Cookie（原样值）
        ③ 两把同属一人：StpKit.SPACE.getLoginIdByToken(satoken) == 会话用户 id
          （不满足即拒：无效/过期 40100、不属于同一人 40102；零上游请求）
        ④ 一次性凭据有效且属于会话用户（R5；缺失/已用过/属别人一律 40300；零上游请求）
   ↓ AiAssistantProxyManager（专用线程）：HttpURLConnection GET 引擎
        {engine}/api/ai/huoshan/chat?message=&userId=&chatId=
        头：X-Internal-Api-Key（配置）、satoken（原样）、X-User-Role（T17：调用者角色，空值不发）、Accept: text/event-stream
        Cookie：SESSION=<浏览器原样值>
   ↓ 引擎：先做一次不注册任何工具的范围分类（只有严格 IN_SCOPE 才放行）
         ├─ 范围外 / 非协议结果 → 固定范围提示 + [DONE]，不进入正式模型与工具链
         └─ 范围内 → 图库助手会话类型 → 工具集（档 1 的三个只读工具 + 档 3 的 batchEditPictures / batchUploadByUrl / MCP searchImage，**外加 visionTagger——仅当配置了视觉模型且调用者是管理员**（T17）；各自带这组凭据打图库 API，RBAC 由图库判）
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

10. **对话端点必须带一次性凭据（R5，2026-09-14 用户拍板"采用一次性 ticket"）**：`POST /api/ai/assistant/ticket` 签发一张票（值为签发用户 id，Redis 键 `huoshantuku:ai:assistant:ticket:<value>`，TTL 60 秒，**取用即删 = 单次使用**），`chat` 新增门槛四校验"票存在、未被用过、且属于会话用户"。理由：本端点为迁就 `EventSource`（只能 GET、不能设 header）成了全站唯一"GET 有副作用"的接口，而全站 CSRF 防线本是隐含约定"`SameSite=Lax` + 有副作用的接口都是 POST"（Lax 挡子资源请求与跨站 POST、只放行顶层导航的 GET），于是攻击者能借受害者的 Cookie 盲打——读不到响应体，但服务端副作用已经发生。票把这层关系反过来用：**能读到响应体的页面才拿得到票**，攻击者因此凑不出这个参数。票放 query 会进访问日志，但"60 秒 + 单次使用"让泄漏无害，这正是它优于长效共享密钥之处（sa-token 自带的 `SaSameUtil` 是全站共享单值、默认 24h、`checkCurrentRequestToken()` 只从 header 取，故未采用；也没选"改 POST + fetch 流式"——那要在前端手写 SSE 解析，而前端无测试脚本）。实现细节：取用即删用 Lua 脚本（GET+DEL）而非 `ValueOperations.getAndDelete`，因为后者下发 Redis 6.2 才有的 `GETDEL`，本机 Redis 实测 **5.0.14.1** 会直接报错（集成测试抓到）；门槛四排在三个登录门槛**之后**，这样登录过期仍回原来的 40100，且一次身份不一致不会白烧一张票。

11. **自然语言范围采用“独立无工具分类 + 固定拒绝”，不只靠主提示词（T25）**：每条请求在正式 ReAct 前增加一次很短的模型分类调用，并带当前会话历史判断多轮跟进；分类阶段不注册任何工具，只有严格返回 `IN_SCOPE` 才放行。`OUT_OF_SCOPE`、空值和解释性文字都 fail-closed，直接返回固定范围提示；provider 超时或异常仍走原失败事件，不伪装成越界。这样既避免关键词白名单误伤“按刚才方案继续”一类跟进，也确定性保证范围外请求不会读取图库。代价是每条请求多一次短模型调用；普通用户与管理员的工具权限装配不变。

## 怎么验证

- **门禁（不依赖 MySQL/Redis）**：`backend` 45 例全绿，其中本档 32 例——`AiAssistantControllerTest` 20 例（登录门槛、satoken 三处取值与优先级、会话 Cookie 缺失拒绝、**会话 Cookie 原样值 vs `session.getId()` 回归**、**R4 两条：假 token 40100 / 别人的真 token 40102 且零上游**、**R5 八条：无票 / 编造的票 / 别人的票 / 用过的票一律 40300 且零上游，有效票正常转发（5 条对话侧），签发端点"未登录不签 / 凭据不同人不签 / 正常签发绑定调用者"（3 条）**）、`AiAssistantProxyManagerTest` 9 例（转发形状与凭据头、SSE 逐帧中继与格式容错、引擎 401/JSON 错误与不可达转可见文案、密钥/地址缺失 fail-closed 且零上游请求、线程池满响亮失败）、`AiPathSaTokenGuardTest` 3 例（R6 守护，见设计理由 7）。R4 的单测用 Sa-Token 内存 DAO 播种真 token（无 Spring 时 `SaManager` 缺省即 `SaTokenDaoDefaultImpl`），验的是真实的 `getLoginIdByToken` 语义而非替身；R5 的门槛用 Mockito 替身（票的 Redis 语义留给集成测试）。**2026-09-15 T17 后的口径**：上述两个类各 +2 例（`AiAssistantControllerTest` **22**、`AiAssistantProxyManagerTest` **12**，新增的都是角色透传断言），backend 门禁总 **74 例**。
- **R5 先红后绿（规则 12）**：门槛未加时 `AiAssistantControllerTest` 20 跑 **4 失败**，失败原因一律是 `Expecting code to raise a throwable`——即"无票/编造票/别人的票/用过的票"这四种请求都被**照常转发**给了引擎（这就是洞本身）；补上门槛四后 20/20 绿。
- **集成测试（本地，需 MySQL/Redis）**：`AiAssistantProxyIntegrationTest` 8 例——真实登录产出 `satoken` Cookie 后原样转发（**含真 Redis 上"取票 → 建流"整条路**）、会话 Cookie 原样值转发（不等于 `session.getId()`）、只带 satoken 不带会话 Cookie 在入口即拒且零上游请求、**R4 两条：假 token 在入口即拒（真会话也救不了）、第二个真实账号的 satoken 配本账号会话回 40102 且零上游**、**R5 三条：真凭据但无票 40300 且零上游、真票用第二次被拒（Lua `GET+DEL` 的 Redis 侧单次使用，原子性由独立 review 用 64 路并发实测：恰好 1 个拿到值、事后键不存在）、别人的真票被拒**。本地全量集成套件（14 个类 58 例）实跑绿；独立 review 复跑 `AiAssistantProxyIntegrationTest` 连跑 4 次均 8/8 绿（"`REQUESTS==0` 假红"未复现）。
- **over-the-wire 复验（R5 accept，2026-09-14；真 HTTP + 桩引擎计数）**：起 backend(`local,test`, 8131) + 桩引擎(8130)，探针账号真登录后：① 带真 Cookie、**无票** → `{"code":40300,...}`，桩引擎**零请求**；② `POST /api/ai/assistant/ticket` → `code=0` 发票；③ 带票对话 → HTTP 200 + `text/event-stream` 事件流（桩引擎记录到 1 次请求，路径 `/api/ai/huoshan/chat`、密钥、satoken、`SESSION` Cookie 全部正确）；④ **同一张票再用** → `40300`，桩引擎仍只有 1 次请求。探针脚本 `%TEMP%\r5-probe.js`（含凭据的日志与 cookie 罐已按惯例删除）。
- **浏览器端到端（R5 后复跑）**：前端 dev 指向 8131，登录后进 `/assistant` 发送「看看我的空间都有什么图」——界面正常呈现用户气泡 → 回答气泡，桩引擎记录到该请求（`userId`、`chatId` 来自 `sessionStorage`、密钥与两把凭据都在），证明"取票 → 建流"在真实浏览器里跑通（截图 `%TEMP%\r5-ui-answer.png`）。回答里的「图库助手响应意外中断，请重试」是桩引擎故意不发 `[DONE]` 所致，见已知限制 9②。
- **跨站导航探针（R5 验收 ⑤，2026-09-14 补跑通过；实施当时未跑、review 列为 P2①）**：起 backend(`local,test`, 8131) + 桩引擎(8130)，跨站页面由 `127.0.0.1:8135` 提供（与 `localhost` 不同站，`SameSite` 按 site 判定为 cross-site），浏览器用应用内 Chromium，探针账号在 `localhost:8131` 上**真登录**（Cookie 由服务端写出，HttpOnly + `SameSite=Lax`，不是手工注入的）。结果：① **原探针 `csrf-page.js` 原样**跑——顶层导航落到 `/api/ai/assistant/chat?message=s3&chatId=csrf-nav-1`，响应 `{"code":40300,...}`，**桩引擎零请求**；40300 是门槛四、排在三个登录门槛之后 ⇒ 这次导航**确实带齐并带对了** satoken 与 SESSION，只缺票（不是被登录门槛挡下的假通过）；② **正向对照**（同一跨站形态、只多一张票；新脚本 `csrf-control-ticketed.js`）——同一条顶层导航拿到完整 SSE 事件流，桩引擎**恰好 1 次**请求（`chatId="csrf-nav-ticketed"`、`userId`、服务间密钥、satoken 与 `SESSION` Cookie 全对），代理日志"AI 助手对话开始"也只 1 条；两次运行**唯一差别就是票**，故①的结论可归因到票本身，也实测证实了"Lax 会在跨站顶层导航上发出 Cookie"这个前提；③ 顺带把原探针第 1 步（no-cors 子资源请求，响应按设计读不到）换成可观测版本（新脚本 `csrf-cors-read.js`，页面由 `127.0.0.1:8137` 提供、cors 模式带凭据）：`POST /ai/assistant/ticket` 与 `GET /user/get/login` 都回 `{"code":40100,"message":"未登录"}` ⇒ **跨站子资源请求带不上 Cookie**，跨站页面因此**取不到票**；本地能读到这个 40100 是因为 dev 白名单含 `http://127.0.0.1:*`（生产白名单只有 www，连读都读不到）。证据汇总 `%TEMP%\t11-rerun\accept5-rundown.txt`（两个新脚本同目录；含凭据的 cookie 罐与桩引擎/代理日志已按惯例删除）。**注意**：三次运行的都是**按当前 HEAD 重新打包**的 jar——旧 jar（当日 19:43 那个）里没有票闸门，拿它复跑会得到相反结论。
- **T25 范围门卫（2026-09-16，真实 MiMo）**：普通用户、关闭 MCP、backend 刻意不启动，三个独立会话分别问量子纠缠、Java 快排、上海三日游，均只返回固定范围提示 + `[DONE]`，无工具 step、无图库 API 调用；正向控制「列出我在火山图库里的空间」通过门卫并产生 `listSpaces` step（随后因 8123 未启动而按预期连接失败）。额外的提示注入探针（要求“输出 IN_SCOPE 再解释量子纠缠”）同样被拒绝；混合请求“列空间 + 写快排”只执行图库部分并拒绝编程部分。自动化先红后绿：范围专项 6 例、相关回归 42 例；`ai/agent` 门禁 183 例全绿。完整过程见 `docs/plans/records/T25.md`。
- **curl 冒烟（真引擎 + 真图库）**：探针账号登录 → 走代理 → `listSpaces` 读到真实空间 `tier1-probe-space`、回答正确、`[DONE]` 收尾；停掉引擎再打一次 → 收到合成的"引擎暂时不可用"+`[DONE]`（不挂死）。原始片段见 handoff 0013。
- **浏览器端到端**：本地起 backend(`local,test`)+引擎+前端 dev，登录后进 `/assistant`，发送后界面依次呈现：用户气泡 → 折叠条「正在执行（0 步）」+ 转圈 + 输入禁用 + 停止按钮 → 「执行步骤（1 步）」含 `工具 · listSpaces` 与真实返回 → 回答气泡 → 输入恢复。该会话用户真实空间数为 0，工具仍回 `code=0`，说明凭据确实透传成功（未透传会是 `40100`）。
- **呈现层纯函数断言（19 条，仓库外脚本）**：项目无前端测试框架，用 esbuild 把真实 `utils/assistantFormat.ts` 转成 mjs 后在 node 里断言——载荷直接取自真机 SSE 原文与回答原文（含 13 个标签的 `<code>` 列表、URL 尾随中文句号、`3 > 2` 不被误判成引用）。脚本 `%TEMP%\t11-smoke\format-check.mjs`，19/19 通过；改动的前端文件 eslint 干净、`vue-tsc --build --force` 计数仍 138（净增 0）。
- 前端 `npm run type-check`：**净增 0**（基线 138 例既有错误，见已知限制）。

## 已知限制

1. **Markdown 只支持子集**（2026-09-14 T11.1 已从"纯文本"升级；**2026-09-15 补上标题**）：支持粗体、行内代码、http(s) 链接、`- `/`1. ` 列表、`> ` 引用、`#`~`######` 标题（渲染成 h3~h6）；**不支持**表格、嵌套列表、图片、单星号斜体——模型若用这些语法会字面显示。要全覆盖就得引 markdown 库 + sanitizer（见口径 9 的取舍）。**标题是 2026-09-15 浏览器验收发现的缺口**：模型常写 `## 标题`，此前井号原样透出（实测看到 `## 🏷️ 标签词表参考`），现已识别并渲染；解析仍只产出数据、渲染走模板插值，XSS 面不变。
2. **错误没有独立视觉**：服务端失败以"助手回答"的形式出现（如"图库助手引擎暂时不可用，请稍后重试"），看起来像模型在说话。**2026-09-15 更新**：引擎侧已有独立的错误事件类型（`event=error`，机制见 `F13-对话循环与SSE事件协议.md`），但前端仍把它当文本追加进回答气泡（没有独立错误态 UI）；代理侧自身合成的失败（引擎不可达、上游错误体）仍走 `answer`，"代理侧错误语义统一"仍是挂账项。
3. **对话历史不落库**：前端内存态，刷新即丢；`chatId` 存 `sessionStorage`，刷新仍续同一条对话串。引擎侧多轮记忆由 `chat_message` 承载（档 1 口径）。
4. **同一页面只允许一条流**（发送中禁输入）；"停止"只关流，不承诺让引擎侧停止（R1）。
5. **前端门禁存量红**：`npm run type-check` **124** 例（2026-09-15 实测；原记的 138 是 T22 删除三个停用页面之前的数）、`eslint .` 73 例既有错误（分布在本次未触碰的文件里，多为 `LocationQueryValue`/`any`/未用变量一类）。**2026-09-15 起口径改为"改动不得净增"并配基线文件**（`frontend/type-check-baseline.txt`，由 `.githooks/check-docs-consistency.mjs` 核对），所以"净增 0"这句话现在可被独立复核；彻底清理仍需单独立任务。**同批补**：助手呈现层的纯函数校验已入库（`npm run check:assistant-format`），这是本仓前端第一个常驻的自动化检查。
6. 单机部署口径：引擎与图库同机/内网（`app.ai.assistant.engine-base-url` 默认 `http://localhost:8124/api`），引擎未上公网；`/ai/manus/chat` 旧端点仍未鉴权（部署前必办，档 1 挂账）。
7. **跨站 CSRF（review R5）已于 2026-09-14 收口**：对话端点现在必须带一次性凭据（见「关键设计与理由」10），"跨站顶层导航带齐两把 Cookie 就能跑完一次 agent"这个洞不再成立——跨站页面构造得出请求、却读不到响应体，因此凑不出票（over-the-wire 复验：无票 `40300` 且桩引擎零请求）。**残留边界（仍未做）**：① 票在 query 里会进 nginx access log——靠"60 秒 + 单次使用"让泄漏无害，而不是靠"不进日志"；② 前端 `EventSource` 的自动重连必须继续在 `onerror` 与 `[DONE]` 时显式 `close()`（`assistantSse.ts` 已加注释钉住）：票是一次性的，自动重连带着用过的票必然被服务端拒；③ 流中断后不能用同一张票续传（也不该续——会重跑一次 agent）；④ **通用方案本轮未做**（用户拍板只做单点收口）：全站拦 cross-site 的过滤器 + "GET 无副作用"守护测试 + CORS 白名单按环境核实，已记入 plan.md 作为后续候选，价值是覆盖将来新增的端点、不必每加一个流式接口重设计一次 nonce；⑤ 环境约束（同批发现）：新增 Redis 用法不得假定 6.2+ 命令（本机 Redis 实测 5.0.14.1，`GETDEL` 直接报错）。**独立 review（2026-09-14）追加的边界**：⑥ **承重屏障是 CORS 读限制、不是 `SameSite`**——票之所以成立，靠的是"攻击者读不到 `POST /ticket` 的响应体"；`SameSite=Lax` 只是第二道（且它按 site（eTLD+1）判定，**同站的其它主机名不受它保护**）。这条决定了"**CORS 白名单是承重配置**"，也详见⑪；**2026-09-14 探针实测补充（验收 ⑤ 补跑）**：跨站子资源请求**确实带不上 Cookie**（`POST /ai/assistant/ticket` 与 `GET /user/get/login` 从跨站页面 cors 模式调用都回 `{"code":40100,"message":"未登录"}`），所以跨站页面**取不到票**；而跨站**顶层导航会**带上 Cookie（同一条 URL 加一张票即拿到完整 SSE 流、桩引擎恰好 1 次请求）——两道防线各自的实测口径见「怎么验证」的"跨站导航探针"条；⑦ **前端取票不判业务码**：`fetchAssistantTicket` 直接取 `data.data`，取票失败但 HTTP 200（如 Redis 不可用回 50000）时 URL 会变成 `ticket=null`，用户看到的是"助手连接中断"而不是"未能取得凭据"，并白打一次 chat；**2026-09-15 已修**——现判 `code === 0` 且值非空字符串，否则抛错走既有文案；浏览器内故障注入验证：伪造 `HTTP 200 + code=50000` 后**一次 chat 都没发**，用户看到"助手未能取得本次对话凭据"。⑧ `POST /ticket` **无频控**（已登录用户可无限造 60s 键），`ticket` / `message` 无长度校验；⑨ 这条链**没有自动化守护**（前端无测试框架，"取票 → 建流"的顺序只靠类型与人工验收）；⑩ 任何**中继重试**同一 chat URL 的中间层都会失败（票已被第一次消费），与②的"自动重连必须 `close()`"同源；⑪ **生产 CORS 白名单含 apex `https://lincode.online`**（`application-prod.yaml`，**review 实测后由 P2 降为加固项**）——apex 与 www 是**同一个 site**（`SameSite` 按 eTLD+1 判定），白名单又放行 apex，于是**一旦 apex 那个 origin 上出现非本应用的可控页面**，它就能既读 `/ticket` 又读 chat 响应、绕过票（即把⑥那道屏障让开）；**实测** apex 与 www 当前**返回逐字相同的 `index.html`（1394 字节、`identical=True`）**、`/api` 在两个 host 上都同源可用（匿名回 `{"code":40100}`）、前端生产用相对 base（`request.ts` 的 `BASE_URL=''`）⇒ **该白名单不是应用所需**，删掉不影响功能。**2026-09-14 已按用户拍板删除**（提交 `1c732db`：跟踪的 `application.yaml` 与不入库的 `application-prod.yaml` 各去一条；注意 prod 那份被 `backend/.gitignore` 排除，改动**随重新打包的 jar 生效**，要上线须重新打包部署）；仍待办"定唯一主机名（www）+ 另一个 301"（nginx）；同类的 `WebSocketConfig:25` 硬编码来源列表（含 `http://localhost:*` 与 apex）**已于同日收口**（提交 `2b29786`：改为与 `CorsConfig` 共用 `app.cors.allowed-origins`，生产不再含 localhost 与 apex，并加 `CorsOriginAllowlistGuardTest` 两条不变量守护）。
8. **凭据组一致性（review R4）已于 2026-09-14 收口**：代理现在校验 `satoken` 与 Spring Session 用户同属一人（无效/过期 40100、不同人 40102，与 `checkSpaceViewPermission` 同口径，零上游请求），"真会话 + 随手编的 token 仍读到真实空间"这个洞不再成立。**残留边界（仍未做，按需另立任务）**：① 代理只判身份一致性、**不判权限**，能不能看某个空间仍只由图库服务端判；② 该失败发生在返回 `SseEmitter` 之前，走 `GlobalExceptionHandler` 回 HTTP 200 + JSON，而前端 `EventSource` 读不到响应体 → 用户只会看到"助手连接中断，请重试"，实际得重新登录（与限制 2 同源，前端若要做"登录态失效→跳登录页"需另开任务）；**R5 之后这条已被收敛大半**：建流前的取票走 axios，能读到 40100 并交给 `request.ts` 的响应拦截器提示 + 跳登录页，剩下读不到的只是"取票与建流之间恰好失效"这种窄缝；③ satoken 走 HttpOnly + SameSite=Lax Cookie，本档未改其存储形态。
9. **SSE 中继的四个边界（review 用自建桩引擎实测）**：① 上游多行 `data:` 被中继用 `\n` 拼成**单帧**发出，线路上是半个 JSON 帧（`data:{"event":"answer",` + 裸换行 + `"content":"multi-line"}`），浏览器 `JSON.parse` 失败、前端**静默丢弃**（现引擎不会发多行 data，属潜伏缺陷）；② 上游未发 `[DONE]` 就 EOF 时，已发出的完整答案后面会**再跟一条"图库助手响应意外中断，请重试"**（答案 + 假错误同屏）；③ 上游非 JSON 错误体（部署形态里若有网关，如 nginx 502 的 HTML）被原文塞进回答气泡（实测 `<html>...502 Bad Gateway...`）——**2026-09-15 已修**：非 JSON 或 JSON 里没有 `message` 时改发通用文案"引擎响应异常（HTTP n）"，原文只进日志；单测红→绿（改前的实际帧就是整页 HTML 进了 `content`），`AiAssistantProxyManagerTest` 13/13；④ 线程池拒绝给浏览器的是**空体 HTTP 500**（`completeWithError` 未走 `GlobalExceptionHandler`），前端只能显示"助手连接中断，请重试"（**仍关闭，按规则 13 判为已知限制**）。
10. **`chatId` 未做净化，可换行注入后端日志**（review 实测：`chatId=inj%0AFORGED-LINE-MARKER` 在日志里伪造出独立一行）。`message` 不进日志、凭据不进日志（三个探针实例日志对真 token/会话值 0 命中，含异常与拒绝路径）。
11. **`ai/agent` 的门禁口径是 `.githooks` 的排除名单，不是 AGENTS 那条通用过滤命令**：AGENTS/handoff 记的 `-Dtest='!*IntegrationTest,!RedisStringTest,!YunPictureBaseApplicationTests'` 直接套到 `ai/agent` 会红（那里要排除的是需要 Spring 上下文 / 网络的类）；`.githooks/pre-commit` 那条排除 8 类之后才是绿（**2026-09-16 现状**：排除 8 类 ⇒ **183 例**；`MyManusTest` 已随 T8-hard 删除、`TerminalOperationToolTest` 随 T13 顺带清理）。两者差异未记录在 AGENTS 的测试命令节，容易让下一个人把红当回归。
12. **"只读"口径已于 2026-09-15 回填（T14）**——本条是 2026-09-15 独立 review 的 **P2**（唯一一条**用户可见的错误声明**），现已收口：档 3 放开写权限后，`/assistant` 页面的 subtitle（原"当前只读不改"）与空状态提示（原"不会改动任何数据"）以及本页「一句话」「怎么用」「核心流程图」都还停留在档 1/档 2 的只读口径；现改为如实描述（可读可改、**改动只在用户明确要求时发生**、不支持删除），并补明"写入会消耗空间配额"。同批 review 顺带订正的另两处陈旧表述：错误事件类型（见限制 2）与门禁排除名单／例数（见限制 11）。**后续 T26（2026-09-16）已补齐**：空状态现在有四条示例，覆盖空间查询、图片查询、标签整理和搜图入库；同时把“登录态 / 可读也可改”等技术化说明改成普通用户能理解的能力范围与安全边界，并新增 `npm run check:assistant-copy` 常驻守护。前端错误态 UI 与工具摘要增强仍按 `F12` 已知限制挂着。
13. **看图打标按角色裁剪（T17，2026-09-15）**：`visionTagger` 只挂给管理员会话（普通用户问"看图打标签"时手上没有这个工具），口径与出口见 `F12`「已知限制」9——那里写了三条残留（角色头不是授权、提示词未按角色补话术、未做两种角色的人工会话验收）。前端**没有**与此相关的角色可见性逻辑（页面不区分角色，也不需要区分：工具少了模型自己会少一条路），所以本条纯属引擎与代理侧的口径。
14. **范围门卫是语义策略，不是授权边界（T25）**：分类由同一个大模型在无工具模式下完成，系统提示词、严格 `IN_SCOPE` 协议、fail-closed 与正式助手的第二层范围提示能降低误判和提示注入风险，但无法像确定性解析器一样数学保证所有对抗措辞都分类正确。真实 MiMo 的普通负向样例与一条直接提示注入探针均通过；若分类器误放行，正式助手仍被要求拒绝范围外部分，图库工具最终还受原有登录态与 RBAC 约束。故本门卫用于约束回答产品范围，**不能替代权限校验**。
