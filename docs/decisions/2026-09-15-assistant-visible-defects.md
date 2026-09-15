# 2026-09-15 — 助手的三处用户可见缺陷收口（两条 P2 + `##` 标题）

> 详情文件：自 `docs/decisions.md` 引用。口径以 `decisions.md` 表内那一行为准。

## 决定

用户 2026-09-15 指示"接着做"，据此收口三处**用户能看见**的缺陷——两条是 2026-09-15 独立复核提升的 P2，一条是同日 T17/T22 浏览器验收新发现的。属规则 3 第④类（用户可见行为变更），故先落本字据。

## 改什么

| # | 缺陷 | 改法 | 位置 |
|---|---|---|---|
| 1 | **上游非 JSON 错误体原文进回答气泡**（P2）：引擎或网关回 200 + 非 JSON 体（如 nginx 502 的 HTML）时，`extractMessage` 回退成"截断后的原文"，用户会看到一坨 HTML | 非 JSON 时**不再把原文当文案**，改用既有的通用文案"引擎响应异常（HTTP n）"；**原文转进日志**（运维仍拿得到细节）。同时删掉因此不再使用的 `ERROR_TEXT_MAX_CHARS`（规则 15：不留死代码） | `backend/.../manager/ai/AiAssistantProxyManager.java` |
| 2 | **前端取票不判 `data.code`**（P2）：`fetchAssistantTicket` 直接取 `response.data.data`，Redis 故障等业务失败（HTTP 200 + `code=50000`）时拿到 `null` → URL 变 `ticket=null` → 用户看到"助手连接中断"，而真实原因是"没取得凭据"，还白打一次 chat | 判 `code === 0` 且值是非空字符串，否则**抛出带服务端 message 的错误**；调用方（`AssistantPage.send`）**已有** catch 分支显示"未能取得本次对话凭据"，不必改页面 | `frontend/src/api/assistantController.ts` |
| 3 | **回答里的 `##` 标题字面显示**：模型常用 `## 标题`，而前端自写的 Markdown 子集只认 `**粗体**`/`` `代码` ``/链接/`- ` 列表/`> ` 引用，于是井号原样透出（验收实测看到 `## 🏷️ 标签词表参考`） | `parseAssistantText` 认 `/^(#{1,6})\s+/` → 新增 `heading` 块（带 level）；渲染成真标题标签 `h(min(level+2, 6))`（页面主标题是 h2，故从 h3 起算）。**仍不产出 HTML**，沿用模板插值，XSS 面不变 | `frontend/src/utils/assistantFormat.ts` + `components/assistant/AssistantText.vue` |

## 一处"重开已关闭项"的说明（按规则 13 需交代）

2026-09-15 同日我把「`visionTagger` 步骤行没有中文别名与摘要」按规则 13 **判为已知限制并关闭**了。**本次重开并一并修**，理由：改动位置与第 3 项**完全相同**（`assistantFormat.ts` 的 `TOOL_LABELS` / `summarizeToolResult`），分两次提交只为一条别名反而增加噪音；且它在验收里是**实测到的观感缺陷**（其余工具都渲染成「工具 · 查询空间列表 / 共 37 个空间」，只有打标那行是裸工具名）。

顺带把另外几个已知工具名也补上（`batchEditPictures` / `batchUploadByUrl` / `searchImage`），并为能识别载荷形状的工具加一句话摘要（`visionTagger` → "建议 N 张（未落库）"、`batchUploadByUrl` → "入库 N 张"、`batchEditPictures` → "提交 N 张"、`searchImage` 非 JSON 的逗号分隔 URL 串 → "搜到 N 张图"）。

## 验证方式（规则 12：先看到红）

- **后端**：`AiAssistantProxyManagerTest` 新增用例——上游 200 + 非 JSON 体（模拟网关 HTML）时，给用户的文案**不得包含原文**、且应是通用文案。**先跑一次看到红**（改前的实现会带出原文），再修到绿。
- **前端**：本仓前端无测试框架（AGENTS.md 已记），故按 T11.1/T16 的先例用 esbuild 把**真实的** `assistantFormat.ts` 转译后在 node 里断言。**与先例不同的一点**：这次**把校验脚本入库**（`frontend/scripts/check-assistant-format.mjs` + `npm run check:assistant-format`），因为规则 12 要求"复现测试永久保留"，而此前两次的脚本都只留在 `%TEMP%`、随目录消失（规则 16 的同类问题）。用例取自**验收实测的真实报文**（含 `## 🏷️ 标签词表参考` 那条回答、`visionTagger` 的真实载荷）。
- **浏览器**：改完起三服务，重跑一次管理员会话，确认标题渲染成真标题、步骤行显示中文别名与摘要。
- **门禁**：backend 单测 + `ai/agent` + `type-check` 基线（不得净增）。

## 不做

- 不动 `AiAssistantProxyManager` 的其它错误分支（relay 半帧、缺 `[DONE]`、线程池拒绝仍按规则 13 关闭）。
- 不给前端引入 markdown 库或测试框架（沿用 T11.1"不引库、不产出 HTML"的口径）。
- 不处理历史探针数据（`plan.md`「数据卫生」已单独登记，需用户点头）。
