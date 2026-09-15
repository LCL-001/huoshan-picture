# F13 对话循环与 SSE 事件协议（T8-hard 循环收敛）

> 覆盖任务：docs/plan.md T8 的循环收敛部分（档 3 的 **T8-hard**）与 T8-c（失败/超时改发显式 `error` 事件）。
> 这是**引擎内部机制**的讲解，不是用户可见功能——用户可见的助手能力见 `F11-AI助手MVP.md`（档 1/2 链路）与 `F12-图库助手档3工具与联调.md`（档 3 工具）。
> 口径全文见 `docs/decisions.md` 2026-09-15 三条；计划与逐项验收见 `docs/plans/2026-09-15-T8hard-循环收敛实施计划.md`。

## 一句话

引擎的对话循环从"两套循环 + 一堆受保护字段旁路"收敛成**一套循环 + 一个事件接口**：`step()` 直接产出事件载荷，消费端只认 `AgentEventListener`，SSE 帧协议（`step`/`answer`/`metrics`/`error`/`[DONE]`）由显式契约测试钉住；顺带修掉了三处协议缺口——最后一次**双发 `[DONE]`**、**校验失败的流不以 `[DONE]` 收尾**（代理会因此再补一条回答）、**失败时把厂商原文当回答重复多轮**。

## 怎么用

对上层（两个控制器）没有变化：`agent.runStream(message)` 照旧返回 `SseEmitter`。变化都在引擎内部与协议契约上：

- **写新 agent**：继承 `ToolCallAgent`（或 `ReActAgent`），`step()` 返回 `List<AgentEvent>`；不要再往 `BaseAgent` 上挂"给循环读的受保护字段"。
- **写新的事件类型**：改 `AgentEvent`（密封接口）→ `SseAgentEventListener.frameOf()`（`switch` 穷尽性会强制你处理）→ `SseEventProtocolContractTest`（帧级断言）→ 前端 `frontend/src/utils/assistantSse.ts`。四处缺一不可。
- **要看协议长什么样**：直接读 `SseEventProtocolContractTest`——它是契约的唯一权威表述。

## 怎么实现

### 关键文件

| 文件 | 作用 |
|---|---|
| `ai/agent/.../agent/BaseAgent.java` | 唯一循环 `runLoop(prompt, listener)`；`runStream()` 变薄壳（备 emitter + 挂连接回调 + 异步驱动循环）；`createEmitter()` 测试缝；失败文案与超时判定（沿 cause 链） |
| `ai/agent/.../agent/ReActAgent.java` | think→act 流转；**不再吞异常**（失败沿栈上抛给循环统一收尾） |
| `ai/agent/.../agent/ToolCallAgent.java` | `think()` 调模型、`act()` 执行工具并**组装事件**（思考帧 + 工具帧，或一条回答帧） |
| `ai/agent/.../agent/event/AgentEvent.java` | 事件协议本体：`Step` / `Answer` / `Metrics` / `Error` / `Done`（record 组件名即帧字段名） |
| `ai/agent/.../agent/event/AgentEventListener.java` | 事件消费者接口（替代原先的受保护字段通道） |
| `ai/agent/.../agent/event/SseAgentEventListener.java` | 事件 → SSE 帧；`Done` → 原始文本 `[DONE]` + `complete()`；写失败回调整停止标记 |
| `ai/agent/.../test/.../event/SseEventProtocolContractTest.java` | **协议契约**（帧级 / 循环级 / runStream 级 / 断流） |
| `ai/agent/.../test/.../agent/AgentFailureEventTest.java` | 失败与超时的收尾契约（超时两种形态 + 通用失败 + 循环兜底） |
| `frontend/src/utils/assistantSse.ts` | 前端消费端：按 `event` 分流，认 `error` |
| `backend/.../manager/ai/AiAssistantProxyManager.java` | 代理：逐帧原样中继 `data:`，认 `[DONE]` 收尾（**不支持的事件类型也原样穿过**） |

### 核心流程

```
runStream(prompt)
  └─ createEmitter()                       // 测试可覆写为记录型 emitter
  └─ 挂 onError / onTimeout / onCompletion  // 只改状态与停止标记，不写数据
  └─ CompletableFuture.runAsync(runLoop(prompt, new SseAgentEventListener(emitter, () -> stopped = true)))

runLoop
  ├─ 校验失败（非 IDLE / 空提示词）→ Answer + Done，直接返回（不进入循环）
  ├─ while (currentStep < maxSteps && state != FINISHED && !stopped)
  │    ├─ step() → 逐条 listener.onEvent(...)        // 工具步 2 条（think + tool），回答步 1 条
  │    └─ isStuck() → handleStuckState() → 达阈值则发 Answer("检测到循环…") 并 break
  ├─ 未停止：达上限则发 Answer("达到最大步骤") → runSummary() 非空则发 Metrics
  ├─ 异常：state=ERROR + 日志（带 agent 名与堆栈）→ 发**一条** Error（固定文案，原文只进日志）
  └─ finally：发 Done（每条流恰好一个）→ cleanUp()
```

事件到帧的映射（字段名即契约）：

| 事件 | 帧（`data:` 后的内容） |
|---|---|
| `Step("think", "思考", t)` | `{"event":"step","kind":"think","name":"思考","content":t}` |
| `Step("tool", "listSpaces、listPictures", t)` | `{"event":"step","kind":"tool","name":"…","content":t}` |
| `Answer(t)` | `{"event":"answer","content":t}` |
| `Metrics(map)` | `{"event":"metrics", …map 平铺}` |
| `Error(t)` | `{"event":"error","content":t}` |
| `Done()` | `[DONE]`（**原始文本，不是 JSON**） |

### 关键设计与理由

1. **`step()` 返回事件列表，而不是让循环读受保护字段**：原先循环靠 `lastStepKind`/`lastThinkText`/`lastToolNames` 三个可变字段判断"这一步是过程步还是回答"、工具名是什么——这条旁路让"抽事件消费者接口"抽不干净，也没法在测试里直接断言分类。改成 `step()` 直接产出事件后，"分类"变成返回值的一部分。think 文本不再跨层写字段：`ToolCallAgent.act()` 从自己的 `toolCallChatResponse` 里取（同一个对象，零额外状态）。
2. **`step()` 可以产出 0..n 条事件**：工具步天然是两帧（模型的自然语言推理 + 工具结果），回答步是一帧，空列表表示"这一步不发帧"。用 `List` 而不是单个对象，避免再引入一个"多帧"的包装类型。
3. **`Done` 由循环的 `finally` 统一发**：这是"每条流恰好一个终止帧"的实现方式，也是三处缺口里两处的修复点（卡死路径原先在 `break` 前多发一次、校验失败路径原先根本不发）。前端与代理都靠 `[DONE]` 判断"正常结束"，缺了它代理会判"响应意外中断"并**再补一条回答**。
4. **失败收敛到唯一的 `catch`**：`ReActAgent.step()` 与 `ToolCallAgent.think()` 都不再吞异常（原先 `think()` 把异常写进 messageList 并 `return false`，循环不终止 ⇒ 同一段原文重复 3~5 轮）。失败只有一个出口：循环的 `catch` → 一条 `Error` + `Done`，`state=ERROR`。
5. **超时只看 cause 链**：外层异常类型由框架决定、随版本变化（T6.1 与 R3 两次实测的外层就不一样），所以沿 `getCause()` 找 `TimeoutException`（流空闲超时）/`HttpTimeoutException`（连接、首包超时）。
6. **面向用户的文案固定、原文只进日志**：原始异常里带厂商地址、状态码与报错正文，对用户是噪声、还可能泄露部署细节；排查靠日志（带 agent 名与完整堆栈）。
7. **`createEmitter()` 缝 + 记录型 emitter**：与后端 `AiAssistantProxyManager` 同一手法（仓内既有先例），让 `runStream` 的异步路径也能对帧断言；`runLoop` 本身保持可同步驱动（测试里配收集型监听器），避免为断言引入等待与抖动。
8. **协议契约测试与实现分离**：契约测试读的是"事件 → 经真实序列化路径 → JSON 字段"，而不是字符串比较，改字段名/改事件名都会红；同时它用三种人为改坏（删 `[DONE]` / 改字段名 / 恢复双终止帧）验证过自己不是空过。

## 怎么验证

- **门禁**：`ai/agent` **143 例**绿（开工基线 118，新增契约 16 + 失败收尾 6 + 迁移中净增 3）；backend **48 例**绿（新增 1 例 error 帧透传守护）。
- **契约测试**：`SseEventProtocolContractTest`（帧级 7 / 循环级 7 / runStream 级 3）。
- **先红后绿**：`AgentFailureEventTest` 修复前 5/5 全红（红输出 `expected: 1 but was: 5`），修复后全绿。
- **负向控制**（临时改坏 → 必须红，改回 → 绿）：删掉收尾 `Done` → 7 例红；`content` 改名 `text` → 3 例红；恢复双 `[DONE]` → 循环级 `Done==1` 断言红。
- **真机逐帧比对**（改造前用 `git worktree` 检出 `9daa286` 单独打包，改造后用 HEAD，同一句只读提问走 backend 代理 → 引擎 → 图库 API）：帧形态与字段集合**逐个一致**，终止帧各恰好 1 个。证据 `%TEMP%\t8\summary.txt` + `before-raw.txt` / `after-raw.txt`。
- **真机失败对照**（provider 返 400 的桩）：改造前 6 条内容相同的 `answer`（厂商原文重复 6 次）+ `[DONE]`；改造后 **1 条 `error` + `[DONE]`**。证据 `%TEMP%\t8\before-error400.txt` / `after-error400.txt`。
- **前端**：`type-check` 138（与基线持平、改动文件 0 命中）、改动文件 eslint 0 错。

## 已知限制

1. **`provider 不可达` 这一族失败仍表现为"连接中断"**：Spring AI 的重试模板会对 `ResourceAccessException` 做指数退避重试（10 次、单次上限 180s），总时长远超引擎 `SseEmitter` 的 300s ⇒ 客户端先被超时掐断，`error` 事件没有机会发出。要收口需在"重试预算 / emitter 超时 / read-timeout 对齐"上做一次决策（`decisions.md` 2026-09-15 已记挂账）。
2. **`metrics` 事件当前无生产者**：`runSummary()` 全仓零覆盖，协议里保留它（前端也按此解析），契约测试用测试子类钉住帧与位置；真要发时注意**键名不得取 `event`/`kind`/`name`/`content`**（指标是平铺进帧的，会覆盖帧字段）。
3. **`runStream` 仍用 `CompletableFuture.runAsync`（`ForkJoinPool.commonPool`）**：一次 50s 级对话会占住共享池线程（并行度≈核数-1）。改专用线程池属运维口径变更，本次未动（`decisions.md` 记观察项）。
4. **`act()` 阶段异常现在也终止本轮**：工具自身已把业务错误转成结构化字符串，所以 `act()` 抛异常意味着意外内部失败；但若有"可重试的工具异常"，现在不会再自动进入下一轮（要重试得在工具内部做）。
5. **前端对 `error` 事件的渲染只是"文本进气泡"**：没有独立错误态 UI，带部分回答时错误文案追加在下面；浏览器端的真实渲染未做人工验收（服务端帧与代理透传均已实测，前端改动只过了 type-check/lint）。
6. **`agent.event` 包的测试替身**（`RecordingAgentEventListener` / `RecordingSseEmitter`）在 `src/test` 下，若将来要在别的模块复用需提级。
