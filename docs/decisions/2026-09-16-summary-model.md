# 摘要压缩改走主脑（2026-09-16）

> 对应 `docs/decisions.md` 的 2026-09-16 一条。理由与证据留在这里，口径以本表为准。

## 决定

`FlowWindowBasedChatMemory` 的摘要压缩**优先用图库助手主脑**（`OpenAiChatModels.assistant()`，OpenAI 协议，prod 上是 MiMo `mimo-v2.5`）；**主脑未配置时**才回落到容器默认 `ChatModel`（由 `spring.ai.model.chat` 决定，本仓是 DashScope）。每次摘要调用把实际用的那个打进日志（`模型来源: 图库助手主脑` / `容器默认模型`）。

## 为什么

起因是 2026-09-16 的 prod 启动实测（commit `9a263a9`）：引擎**必须给一个真实可用的 DashScope key**，而助手的"大脑"明明是 MiMo。当时实测两种起不来的形态：

| 做法 | 结果 |
|---|---|
| `AI_DASHSCOPE_API_KEY` 为空 | 启动失败：`DashScope API key must be set` |
| 把 `spring.ai.model.chat` 关成 `none` | 启动失败：`Parameter 1 of constructor in FlowWindowBasedChatMemory required a bean of type ChatModel that could not be found` |

而它**不是摆设**：`summarize()` 里 `chatModel.call(...)` 走的就是那个默认模型——给个假 key 能启动，**会话一长摘要就失败**（失败后降级为 `trimByTokens` 硬裁剪，用户不会看到报错，但"很久之前聊了什么"这件事会悄悄失效）。

摘要与主流程同源是有道理的：它本来就是"同一场对话的另一段"，没有理由让第二家厂商承担它。

## 证据（可复跑）

改动分两步做，先看红再看绿（AGENTS.md 规则 12 的口径）。

命令（均在 `ai/agent`）：

```
mvn -B test -Dtest="FlowWindowBasedChatMemory*Test" -Dsurefire.failIfNoSpecifiedTests=false
```

**第一步：只改结构（注入 `OpenAiChatModels` + 兜底 `ChatModel`），`summarize()` 仍用兜底模型 → 新用例起红**

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.717 s <<< FAILURE! -- in 摘要模型选择：主脑优先、默认模型兜底
[ERROR] com.lcl.myaiagent.chatmemory.FlowWindowBasedChatMemorySummaryModelTest.summaryGoesToAssistantModel -- Time elapsed: 0.558 s <<< FAILURE!
[ERROR]   FlowWindowBasedChatMemorySummaryModelTest.summaryGoesToAssistantModel:104->compressAndCaptureSummary:195
[ERROR] Tests run: 14, Failures: 1, Errors: 0, Skipped: 0
```

失败机理：摘要请求打到的是兜底模型（Mockito 未打桩 → 返回 `null`）→ `summarize()` 判空抛 `模型返回空摘要` → 降级硬裁剪 → `chatSummaryRepository.saveOrUpdate(...)` 从未发生，断言落在这一点上。

**第二步：`summarize()` 改为优先主脑 → 绿**

```
[INFO] Tests run: 5, ... -- in com.lcl.myaiagent.chatmemory.FlowWindowBasedChatMemoryDedupeTest
[INFO] Tests run: 2, ... -- in 水位线复用率测量
[INFO] Tests run: 2, ... -- in 摘要模型选择：主脑优先、默认模型兜底
[INFO] Tests run: 5, ... -- in com.lcl.myaiagent.chatmemory.FlowWindowBasedChatMemoryTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**全量门禁口径（与 `.githooks/pre-commit` 的同一条）**

```
mvn -B test -Dtest="!MyAiAgentApplicationTests,!ProductScopeTest,!PgVectorVectorStoreConfigTest,!FileOperationToolTest,!PDFGenerationToolTest,!ResourceDownloadToolTest,!WebScrapingToolTest,!WebSearchToolTest" -DfailIfNoTests=false
```

```
[INFO] Tests run: 175, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（2026-09-15 记录的基线是 173 例，本次 +2 = 175。）

**装配验证（门禁不覆盖的那部分）**：`@SpringBootTest` 那批被 `.githooks/pre-commit` 有意排除，所以"新加的构造依赖到底能不能装配"门禁是看不见的——单独跑了一次：

```
cd ai/agent && mvn -B test -Dtest="MyAiAgentApplicationTests" -Dsurefire.failIfNoSpecifiedTests=false
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

该测试按默认 `ollama` profile 起上下文：**在没有 DashScope 的形态下，`OpenAiChatModels` 与 `ChatModel` 两个构造参数都能装配**（说明兜底路径在缺默认模型提供方的环境里也成立）。注意它需要本机 MySQL / Redis 在跑，不属门禁口径。

## 新用例怎么证（`FlowWindowBasedChatMemorySummaryModelTest`）

不桩 `OpenAiChatModels`（它是 final 类，而且真正要证的正是"请求真的发到了主脑的 base-url"），改起一个 JDK 自带的桩 HTTP 服务当 MiMo，把打进来的请求记下来：

- **主脑已配置**：`REQUESTS` 恰好 1 条，路径 `/v1/chat/completions`（Spring AI 按协议拼的），请求体含摘要 prompt 的固定片段，摘要正确落库；`verifyNoInteractions(fallbackChatModel)` —— 容器默认模型**一次都不碰**。
- **主脑未配置**：摘要走兜底模型（prompt 一致），`REQUESTS` 为空 —— 主脑端点**零请求**。

两个方向合起来是这次改动的正负向控制。中途还抓到一处测试卫生问题：打点是静态的（桩服务是类级资源），不清会串到下一个用例，已在 `@BeforeEach` 里清。

## 未覆盖 / 残余（**不是"已解耦"**）

- **`AI_DASHSCOPE_API_KEY` 仍必须非空**：入库配置 `spring.ai.model.chat: dashscope` 会急切创建 `dashScopeChatModel` Bean。本次只让它的**值**不必真实可用，没让整个依赖消失。
- **仍真用默认模型的两处**（都是冻结的旧链路，`/ai/manus/chat` 零鉴权 + 只监听回环）：`AiController` → `MyManus` 的对话，以及它顺带触发的 `ConversationTitleService`（会话标题生成，失败已 try/catch 只记 warn）。把它们也摘掉属独立改动。
- 本改动**只在单测层面验证**（桩服务 + 构造注入），**未在真机跑过一次长对话**；prod 侧的验收点是 `deploy/prod-checklist.md` 自检表里的「摘要走主脑」那一条（看引擎日志的 `模型来源`）。
