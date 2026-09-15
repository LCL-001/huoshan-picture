> 实施记录（docs/plans/records/，2026-09-15）

# 助手三处用户可见缺陷收口（2026-09-15）

**一句话**：三条都改完并**逐条验过**——非 JSON 错误体不再进气泡（单测红→绿）、取票业务失败给出准确文案且不再白跑一次 agent（浏览器内故障注入验证）、`##` 标题真渲染 + 工具行有中文别名与摘要（浏览器 + 入库脚本双验）。口径见 `docs/decisions/2026-09-15-assistant-visible-defects.md`（字据提交 `e94ee24`）。

## 改了什么

| # | 文件 | 改动 |
|---|---|---|
| 1 | `backend/.../manager/ai/AiAssistantProxyManager.java` | `extractMessage` 非 JSON / 无 message 字段时**返回 null**（原文只进日志），由既有分支发通用文案"引擎响应异常（HTTP n）"；删掉因此闲置的 `ERROR_TEXT_MAX_CHARS` |
| 2 | `frontend/src/api/assistantController.ts` | `fetchAssistantTicket` 判 `code === 0` 且值是**非空字符串**，否则 throw（带服务端 message）；调用方 `AssistantPage` 的 catch 分支本就有准确文案，未改页面 |
| 3 | `frontend/src/utils/assistantFormat.ts` + `components/assistant/AssistantText.vue` | 新增 `heading` 块（`/^(#{1,6})\s+/`），渲染成 `h(min(level+2, 6))`（页面主标题是 h2，故从 h3 起算）；`TOOL_LABELS` 补 4 个工具；`summarizeToolResult` 增 4 类摘要 |
| 4 | `frontend/scripts/check-assistant-format.mjs`（新）+ `package.json` | 把校验脚本**入库**并挂 `npm run check:assistant-format` |

**重开说明**：第 4 项里的工具别名此前（同一天）被按规则 13 判为"已知限制并关闭"，本次重开是因为改动位置与第 3 项完全相同（同一文件的同一张表与同一个函数），已在字据里交代。

## 验证（规则 12：先红后绿 + 负向控制）

### ① 非 JSON 错误体（后端，单测红→绿）

**红**（改前，`AiAssistantProxyManagerTest#nonJsonUpstreamErrorBodyDoesNotLeakIntoTheAnswer`）：

```
Expecting actual:
  "{"event":"answer","content":"图库助手引擎拒绝了本次请求：<html><head><title>502 Bad Gateway</title></head><body><center><h1>502 Bad Gateway</h1></center><hr><center>nginx/1.24.0</center></body></html>"}"
to contain: "..."
```

整页网关 HTML 就在用户气泡里——缺陷与证据同框。

**绿**：`AiAssistantProxyManagerTest` **13/13**（原 12 + 新增 1）；断言含 `doesNotContain("Bad Gateway")` / `doesNotContain("nginx")` / `doesNotContain("<html>")`。

### ② 取票判 code（浏览器内故障注入）

前端无测试框架，且 `assistantController.ts` 依赖 axios，纯函数脚本覆盖不到。故在**真实页面里**把 `XMLHttpRequest` 打桩，令 `POST /ai/assistant/ticket` 回 `HTTP 200 + {"code":50000,...}`（正是 Redis 故障时后端会有的形态），然后点发送：

- 取票被拦截：`fakedTicketInterceptions = 1`
- **XHR 调用列表里只有取票那一条**——`/ai/assistant/chat` **一次都没发**（改前这里会带 `ticket=null` 白跑一次 agent）
- 用户可见文案：**「助手暂时不可用（未能取得本次对话凭据），请稍后重试」**，而不是"助手连接中断"

### ③ `##` 标题与工具别名（入库脚本 + 浏览器）

**入库脚本**（`npm run check:assistant-format`，21 条断言，用例取自验收的真实报文）：`RESULT: PASS`。
**负向控制**：把 `assistantFormat.ts` 还原成改前版本 → **12 条红**（标题识别、别名、四类摘要全部红），恢复后绿。

**浏览器**（真机三服务，管理员会话，同一句提问）：

```
工具 · 查询空间列表      共 37 个空间
工具 · 读取标签词表      标签 21 个 · 分类 5 个
工具 · 查询图片清单      共 9 张图片
工具 · 看图打标建议      建议 1 张（未写入图库）
工具 · 看图打标建议      建议 7 张（未写入图库）
heading "📁 普通空间（1 张）"    [level=4]
heading "📁 旗舰空间（7 张）"    [level=4]
heading "⚠️ 标签词表提示"        [level=5]
```

`##` → level 4、`###` → level 5，与 `min(level+2,6)` 的映射一致；**全页无字面 `##`**。

## 门禁

- backend 单测：`AiAssistantProxyManagerTest` 13/13；提交时 pre-commit 跑通 backend 全量 + `ai/agent` 全量
- 前端：`type-check` **124 = 基线**（净增 0）；改动文件 `eslint` 干净
- `npm run check:assistant-format`：PASS（21 断言）

## 环境与清理（第二轮验证后）

- 三服务已停：8123 / 8124 / 5173 无 LISTENING；MySQL 3306 / Redis 6379 保持（非我启的）
- 探针数据：`chat_message` / `chat_summary` 按本轮 conversationId 删除（复查 0）；两个 `zprobe_*` 账号删除（复查 0，**用户总数回到 415 未变**）；Redis 删 2 个 satoken 键（复查 0，306 → 304）
- 工作区干净；未调用任何写类工具（本次唯一的"写"是我自己造的探针账号，已删）

## 未验 / 边界

- **截图仍未取到**（全页截图超时，与上一轮同因）；证据以 DOM 快照原文 + 脚本断言 + 单测输出为准。
- 取票那处是**故障注入**验证，不是真实 Redis 故障复现；注入点、伪造响应体形状与实际 Redis 故障时后端返回的形态一致（`HTTP 200 + code=50000`）。
- 模型选择仍有随机性：本轮管理员会话恰好调了两次 `visionTagger`；别名与摘要的正确性不依赖它是否被调用（入库脚本用真实载荷断言）。
