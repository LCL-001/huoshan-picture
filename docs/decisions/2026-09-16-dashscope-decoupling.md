# 引擎侧摘掉 DashScope（2026-09-16）

> 对应 `docs/decisions.md` 的 2026-09-16 一条。理由与证据留在这里，口径以本表为准。
> 本条是同一天两轮指示的合并结果：先"把摘要也接 mimo"，再"仍用默认模型的两处也接 mimo"。

## 决定

引擎（`ai/agent`）不再依赖 DashScope，分两块：

**一、调用点全部改走图库助手主脑**（`OpenAiChatModels.assistant()`，OpenAI 协议，prod 上是 MiMo `mimo-v2.5`）：

| 位置 | 原先 | 现在 |
|---|---|---|
| 会话摘要 `FlowWindowBasedChatMemory.summarize()` | 容器默认 `ChatModel`（DashScope） | 主脑 |
| 旧链路对话 `AiController` → `MyManus`（`/ai/manus/chat`） | 容器默认 `ChatModel` | 主脑 |
| 会话标题 `ConversationTitleService` | 容器默认 `ChatModel` | 主脑 |

**行为口径：主脑是唯一的模型提供方，不做跨厂商回退。**

- 摘要拿不到主脑 → 抛错，由 `compress()` 的 catch **降级为硬裁剪**（`会话摘要压缩失败，降级为硬裁剪` WARN，不是静默）；
- 标题拿不到主脑 → 跳过生成、留默认标题，只记 WARN（标题是锦上添花，不该抛给用户）；
- `/ai/manus/chat` 拿不到主脑 → **入口 fail-closed**（`图库助手模型未配置：请设置 app.ai.openai.assistant.*`）。原先它注入的是容器默认模型，缺配置时要到第一次 LLM 调用才炸，报的还是 DashScope 的口径——照着那句话查不到"该配哪个 key"；
- 每次摘要调用仍把输入/返回长度打进日志。

**二、入库配置关掉整个 DashScope 集成**（`ai/agent/src/main/resources/application.yaml`）：

```yaml
spring.ai.model.chat: none          # 原 dashscope
spring.ai.model.embedding: none     # 原 dashscope
spring.ai.dashscope.enabled: false  # 新增；这条不能省，见下
```

并删掉已无意义的 `spring.ai.dashscope.api-key` 占位与 `chat.options`（`qwen3.7-flash`）。

**结果：prod 不再需要 `AI_DASHSCOPE_API_KEY`**（连占位都不需要）。

## 为什么

起因是 2026-09-16 的 prod 启动实测（commit `9a263a9`）：引擎**必须给一个真实可用的 DashScope key**，而助手的"大脑"明明是 MiMo。当时实测两种起不来的形态：

| 做法 | 结果 |
|---|---|
| `AI_DASHSCOPE_API_KEY` 为空 | 启动失败：`DashScope API key must be set` |
| 把 `spring.ai.model.chat` 关成 `none` | 启动失败：`Parameter 1 of constructor in FlowWindowBasedChatMemory required a bean of type ChatModel that could not be found` |

而它**不是摆设**：`summarize()` 里 `chatModel.call(...)` 走的就是那个默认模型——给个假 key 能启动，**会话一长摘要就失败**（降级为硬裁剪，用户看不到报错，但"很久之前聊了什么"悄悄失效）。

只改摘要还不够：真正让 key 变成必需品的是 `spring.ai.model.chat: dashscope` 会**急切创建** Bean。所以三处调用点与配置要一起动，才谈得上"摘掉"。摘要、标题、旧链路对话本来也都是"同一场对话的另一段"，没有理由让第二家厂商承担它们。

**一个关键发现**：`spring.ai.model.chat: none` **管不到 DashScope 的 agent 那条自动配置**——`DashScopeAgentAutoConfiguration` 另有开关（`spring.ai.dashscope.enabled` / `spring.ai.dashscope.agent.enabled`），key 为空时照样让启动失败，报的还是同一句 `DashScope API key must be set`。所以"关掉 DashScope"必须显式关整个集成。

## 证据（可复跑）

### 单测

命令（均在 `ai/agent`）：`mvn -B test -Dtest="FlowWindowBasedChatMemory*Test,ConversationTitleServiceTest" -Dsurefire.failIfNoSpecifiedTests=false`

第一步只改结构（注入 `OpenAiChatModels`、行为不变）→ 新用例起红：

```
[ERROR] com.lcl.myaiagent.chatmemory.FlowWindowBasedChatMemorySummaryModelTest.summaryGoesToAssistantModel -- Time elapsed: 0.558 s <<< FAILURE!
[ERROR] Tests run: 14, Failures: 1, Errors: 0, Skipped: 0
```

红在哪：摘要请求打到的是兜底模型（Mockito 未打桩 → 返回 `null`）→ `summarize()` 判空抛 `模型返回空摘要` → 降级硬裁剪 → `chatSummaryRepository.saveOrUpdate(...)` 从未发生。

第二步改实现 → 绿；再把三处调用点与配置一并收口后：

```
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0    # chatmemory 包 + 标题服务
[INFO] BUILD SUCCESS
```

**全量门禁口径**（与 `.githooks/pre-commit` 的同一条）：

```
mvn -B test -Dtest="!MyAiAgentApplicationTests,!ProductScopeTest,!PgVectorVectorStoreConfigTest,!FileOperationToolTest,!PDFGenerationToolTest,!ResourceDownloadToolTest,!WebScrapingToolTest,!WebSearchToolTest" -DfailIfNoTests=false
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 0
```

（2026-09-15 基线 173 → 本次 +4：摘要模型 2 例、标题服务 +2 例。）

### 新用例怎么证

`FlowWindowBasedChatMemorySummaryModelTest` 不桩 `OpenAiChatModels`（它是 final 类，而且真正要证的正是"请求真的发到了主脑的 base-url"），改起一个 JDK 自带的桩 HTTP 服务当 MiMo：

- **主脑已配置**：摘要请求恰好 1 条、路径 `/v1/chat/completions`、请求体含摘要 prompt 片段、摘要正确落库；
- **主脑未配置**：零请求、不落摘要、仍返回可用历史（降级硬裁剪）。

`ConversationTitleServiceTest` 两例对称：主脑配了 → 标题经 `normalizeTitle` 后落库；主脑没配 → 不取模型、不写库、不抛。`FlowWindowBasedChatMemoryTest` 里"不调模型"的三处断言也升级为 `verify(openAiChatModels, never()).assistant()`（连"取模型"这一步都没发生，比"模型没被调用"更强）。

### 装配（门禁盲区）

`@SpringBootTest` 那批被 `.githooks/pre-commit` 有意排除，"新构造依赖能不能装配"门禁是看不见的——单独跑：

```
mvn -B test -Dtest="MyAiAgentApplicationTests" -Dsurefire.failIfNoSpecifiedTests=false
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

（需本机 MySQL / Redis 在跑，不属门禁口径。）

### 真机启动四形态（本次最有价值的一条）

`mvn -B -DskipTests package` 后 `java -jar target/my-ai-agent-0.0.1-SNAPSHOT.jar ...`，每次跑完都停进程并核实 8124 无监听、无残留 java：

| # | 启动参数 | 结果 |
|---|---|---|
| 1 | `--spring.profiles.active=local --spring.ai.model.chat=none --spring.ai.model.embedding=none --spring.ai.dashscope.api-key=` | **失败**：`dashScopeAgent ... DashScope API key must be set`（关键发现：`model.chat` 管不到 agent 那条） |
| 2 | 再加 `--spring.ai.dashscope.enabled=false` | **成功**：`Started MyAiAgentApplication in 9.441 seconds` |
| 3 | 入库配置改完后，只给 `--spring.profiles.active=local --spring.ai.dashscope.api-key=`（**不带任何开关**） | **成功**：`Started MyAiAgentApplication in 8.861 seconds` |
| 4 | 把 key 换成**不可解析的占位** `--spring.ai.dashscope.api-key=${AI_DASHSCOPE_API_KEY}`（复刻 prod 那份**不入库** yaml 的写法 + 环境变量缺失） | **成功**：`Started MyAiAgentApplication in 9.034 seconds` |

第 4 条是给 prod 用的：即使 prod 那份 yaml 里还留着 `api-key: ${AI_DASHSCOPE_API_KEY}` 而环境变量没给，**也不会因为解析不了占位符而启动失败**（该属性已没人读）。形态 3、4 的启动日志里都打出了 `OpenAI 协议模型就绪：config=app.ai.openai.assistant, baseUrl=https://api.xiaomimimo.com, model=mimo-v2.5 ... extraBodyKeys=[thinking]`。

## 未覆盖 / 残余

- **三条 MiMo 调用都没有真机跑过**（摘要 / 标题 / MyManus 对话）：单测+桩服务证的是"请求发到主脑 base-url、响应被正确消费"；真实 MiMo 的连通性由 T8-hard / T14 / T15 的实机联调背书（同一 `OpenAiChatModels` 装配、同一条阻塞调用路径，看图打标走的就是阻塞 `call()`）。prod 侧验收点写在 `deploy/prod-checklist.md` 自检表（长对话后应出现 `摘要模型调用完成`、不应出现降级 WARN）。
- **旧链路行为确实变了**：`/ai/manus/chat` 从 DashScope/Qwen 变成 MiMo。它服务冻结的 MyManus 前端，且 D1 后只监听回环；工具调用组合（`ToolCallAgent` 的 `DashScopeChatOptions` + OpenAI 协议模型）与图库助手**同构**，后者已实机验证过工具循环。这是用户明确指示的改动。
- `ToolCallAgent.createChatOptions()` 仍返回 `DashScopeChatOptions` **对象**——它只是个 options POJO，**不依赖 DashScope 集成**（形态 2–4 的启动已证明）。换成 provider 中立的 options 属可选清理，按规则 1 未做。
- 后端（`backend`）与线上未动；F11 仍未勾（等部署）。
