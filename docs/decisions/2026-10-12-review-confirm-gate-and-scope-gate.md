# 2026-10-12 助手「写确认门 + 范围门卫」独立 review（规则 13）

> 只读 review，HEAD `0794374`。按规则 13 换独立视角做：由**两个无实现先见的新上下文子代理**分头评审
> （确认门一个、范围门卫一个），本文件汇总并给出两类处置。**未修改任何文件**；评审只读、无真机/无构建执行。
> 结论口径以 `docs/decisions.md` 2026-10-12 行为准。

## 范围

- **确认门（写操作 prepare→confirm→执行）**：spec 写安全边界、T27.4–T27.10 与 T44/T45 的字据/记录，及 backend/引擎/前端实现。
- **范围门卫（in-scope/out-of-scope 分类）**：`HuoshanAssistantAgent` 的门卫实现与 T43/T25 记录。

## 一、确认门：**通过**（0 条 P1/P2）

核对结论（均经代码逐处核对）：

1. **模型/恶意调用者无法绕过用户确认写库**：引擎无 confirm 通道（`HuoshanApiClient` 只有读工具 + `prepareOperation`；`HuoshanToolFactory` 不挂任何直写工具）；prepare 只写 Redis 不落库；confirm 唯一调用点是前端卡片按钮；confirm 后仍走既有 service 的登录态/RBAC/额度，不绕过。
2. **原子单次消费成立**：`CONSUME_SCRIPT` 在一个 Lua 内做"存在性 → userId → chatId → digest → `redis.sha1hex(plan)` 完整性 → DEL → 返回"；任一不满足返回空哨兵且**保留原值**，重放必拒。`STORE_SCRIPT` 为 `DEL+HSET+EXPIRE` 原子。
3. **同源保证成立**：`AiOperationConfirmRequest` 只带 operationId+chatId+digest，无业务 payload；卡片摘要与 digest 由**同一份归一化计划**同一次生成，confirm 执行的就是服务端存的计划——"卡片 = 计划 = 执行内容"由构造保证。
4. **无 TOCTOU / 重复执行 / 跨用户 / 跨会话洞**：单次消费原子；userId/chatId 绑定；confirm 是 POST（Lax 下跨站带不来凭据组）且要全量 `requireTrustedCaller`。执行失败即丢失该 operation（须重新 prepare），fail-closed 而非重复执行。
5. **T44 结果事实通道**：新端点仅服务间密钥可调，写 USER 角色 + `【系统记录】` 前缀进 `owner(userId)+chatId` 会话。**属同一信任类的写入面**（`/chat` 本就能让持钥方写进同一会话记忆），非新信任边界；生产内容由 backend 生成、不含用户 payload。
6. **T45 提前收尾无副作用**：仅在出现 `pending_confirmation` 时置 FINISHED，且在 askHuman/doTerminate 分支之后；循环正常收尾恰好一个 `[DONE]`；MyManus 工具不产该载荷，无回归。

### 确认门发现

| 项 | 严重度 | 处置 |
|---|---|---|
| **prepare 泄露任意空间名（IDOR）**：`resolveSpaceName` 只 `spaceService.getById` 无归属校验，`/operations/prepare` 只要求登录——任一登录用户可拿别人的 `spaceId` 换到卡片摘要里的空间名（T27.10 引入；与 decisions 2026-09-13"读接口必须绑定目标资源"相抵）。影响低（仅空间名，且需已知雪花 id） | P2/P3 | **现在修**（T47，廉价、用户不可见） |
| T44 结果事实通道是"对用户隐藏的写入面"（持钥方可注入任意文本进任意用户会话记忆） | P3 | **判已知限制关闭**（写入 F12 已知限制） |
| `VisionTagResult` / `VisionTagSuggestion` javadoc 仍引用 T27.6 已删的 `batchEditPictures` 工具 | P3 | **判已知限制关闭**（纯注释误导，可随手修） |

## 二、范围门卫：**有条件通过**（1 条 P1 + 3 条 P2）

机制核对（本文件作者已复读 `HuoshanAssistantAgent.java:147-270`，确认如下）：

- 门卫对**每条**请求多付一次**阻塞、非流式**模型调用，用的是**主脑同一个模型**（`:127,163`）。
- 门卫输入 = `System(SCOPE_PROMPT)` + `lastAllowedRoundContext()`（至多 1 轮）+ 本轮消息（`:172-181`）。
- `lastAllowedRoundContext()` 以 USER 行划轮，**只取 AssistantMessage 的文本与 toolCalls**（`:188-222`）；USER 行文本**从不进输入**。

### 发现

| # | 严重度 | 发现 | 处置 |
|---|---|---|---|
| **F1** | **P1** | **门卫看不见 T44 的「操作结果」事实行**（USER 角色 → 只作轮边界、文本不可见），且额外吃掉一个轮边界把窗口顶回更早；叠加"回放剥掉 toolCalls"，一次**已完成写操作**的追问（真机实测「刚才那个批量编辑完成了吗？结果怎么样」）被误拒。**与 T44 的初衷直接矛盾**：T44 为"下一轮能读"落了事实，却把守这一轮的门卫对它是瞎的 | **现在修**（T46） |
| **F2** | P2 | **工具名信号在生产里恒空**：`MessageConverter.toReplayMessage` 回放时 `new AssistantMessage(text)` 丢掉 `toolCalls`（`:149-154`），而门卫读的正是回放视图（`BaseAgent` 载入 `historyContext`）——`composeRoundContext` 里"（上一轮工具：…）"永不出现。对应单测用裸 memory，绿灯但**不代表生产** | **现在修**（T46） |
| **F3** | P2 | **判定解析脆弱 + 能力枚举缺写操作**：首个 `IN_SCOPE\|OUT_OF_SCOPE` 子串定生死，`SCOPE_CAPABILITIES_EN`（`:47-48`）只列"整理/搜图/导入"，未明写"批量编辑图片"；否定式前言（"这不是 OUT_OF_SCOPE…"）会被首个 OUT_OF_SCOPE 否决 | **现在修**（T46） |
| **F4** | P2 | **1 轮窗口丢掉任务锚点**：追问依赖的空间名/图片/操作类型往往在 2+ 轮之前；真机上只有**在当轮消息里重述锚点**（"T44 smoke space…图片…整理"）才放行——召回实质上是**词法级**的 | **现在修**（T46） |
| F5 | P3 | 每轮多一次阻塞模型调用（同主脑、在关键路径上，重试再付一次） | **判已知限制关闭**（F11 关键设计 11 已记） |
| F6 | P3 | 误拒话术被写回 assistant 行（T43 刻意"拒绝一等公民"），使误拒在回放里可见且粘性 | **判已知限制关闭**（F11 已知限制 14 已含方向；属 T43 设计选择） |
| F7 | P3 | 误放面（带平台关键词的通用请求、首 token 注入、混合请求） | **判已知限制关闭**（F11 已知限制 14 主体判定：门卫是语义策略不是授权边界，第二层提示 + 工具侧 RBAC 兜底） |

### 召回改进方向（T46 备选，按杠杆排序，均保持 fail-closed）

1. 门卫输入纳入**最近一条 USER 轮**（含 T44 事实行）或其摘要——直接补上追问依赖的锚点；事实行是 backend 生成的机器事实，纳入无信任问题。
2. **保留工具名信号**：门卫改为读原始 `chat_message` 行（元数据里有 `toolCalls`），或让回放视图对门卫保留工具名。
3. **廉价放行前置检查**（只加允许方向，安全）：当轮或上一轮出现确定性信号（已知空间名 / 上轮工具名 / 操作结果事实 / 平台关键词）即短路 IN_SCOPE，省掉一次模型调用；不重蹈 T25"关键词白名单当唯一机制"的覆辙。
4. **判定解析健壮化**：忽略否定式前言；能力枚举补上写/编辑操作。
5. 若必须保 1 轮硬上限：维护一条跨轮"当前任务"注记（空间 + 操作状态），门卫恒可见。

## 三、两类处置清单（规则 13 输出契约）

**现在修**（建任务、走正常流程；均属用户可见行为变更 → 先立字据）：
- **T46 门卫召回第二轮**：F1（P1）+ F2/F3/F4（P2）。文件范围限引擎 `agent/HuoshanAssistantAgent.java`（+ 读取 `chat_message` 的缝隙）+ 其范围测试；不碰 backend/前端/循环本体。
- **T47 prepare 空间名权限校验**：确认门 Finding A（IDOR）。文件范围 `manager/ai/AiAssistantOperationManager.java` + 测试。

**判已知限制并关闭**（写进功能文档「已知限制」即算收口，不建任务、不立字据）：
- F5 / F6 / F7 → F11 已知限制 14（补记 2026-10-12）。
- 确认门 T44 隐藏写入面 → F12 已知限制 11。
- 陈旧 javadoc → 记为可随手修的注释债（无行为影响）。

## 四、未能验证

- 无真机/无构建执行（只读评审）；Lua 原子性与 sha1 结论来自代码核对 + 已入库的真 Redis 9/9 与真机记录，非本次复跑。
- 未核验 prod 拓扑（引擎 8124 `/ai/huoshan/**` 是否仅 backend 可达）——该点是"持钥方=可信"结论的承重点，依赖 2026-09-14 stage1 字据，未看实际 nginx。
- 未做浏览器端人工验收（双击/取消/刷新路径依赖 T27.8 真机记录）。
