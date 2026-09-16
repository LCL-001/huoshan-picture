# T27 第一阶段 — 首次公开部署最小安全收口

## 放行范围

2026-09-16 用户确认先完成四项：

1. T27.1 凭据传输与重定向加固；
2. 确定性确认门完成前，暂时摘掉助手的两个直接写工具；
3. Prompt、工具参数/结果和未知工具异常脱敏；
4. 页面文案、示例与功能讲解同步为当前只读/建议能力。

行为口径见 `docs/decisions/2026-09-16-first-release-readonly-assistant.md`。本记录只保存实施过程与可复跑证据。

## 1. 凭据传输与重定向

### 改动

- `AiAssistantController` 不再接受 query 中的长期 Sa-Token，只接受配置名对应的请求头或 HttpOnly Cookie；
- Controller 与 Proxy 统一读取 `${sa-token.token-name:satoken}` 和 `${server.servlet.session.cookie.name:SESSION}`，不再一侧可配置、一侧硬编码；
- `AiAssistantProxyManager` 设置 `setInstanceFollowRedirects(false)`，3xx 不自动跟随，内部 API Key 和用户凭据不会被继续转发到重定向目标。

### 先红后绿

- query Sa-Token 负向测试修复前失败：预期抛错，但请求仍被接受；修复后拒绝且上游请求数为 0；
- 重定向探针修复前实际产生 20 次请求，预期 1 次；修复后只请求原始上游 1 次；
- 新增非默认 token/Cookie 名契约测试，确认 Controller 取值与 Proxy 转发一致；
- 聚焦 backend 37 例通过；提交门禁 backend 77 例、`ai/agent` 186 例通过。

代码提交：`bf688f2 fix: 加固助手代理凭据转发`。

## 2. 暂停直接写工具

### 改动

- `HuoshanToolFactory` 当前只装配三个只读图库工具、管理员可选的 `visionTagger` 和额外 MCP 工具；
- `batchEditPictures` / `batchUploadByUrl` 的类与单测保留，但模型当前看不到、调不到；
- 普通图库页面的批量编辑、URL 上传和管理端 AI 打标不受影响；
- `HuoshanAssistantAgent` 明确当前只读：不得声称修改、上传或删除；搜图只返回候选；看图只返回建议。

### 先红后绿

- 装配测试修复前 4 条失败，工具列表仍含两个写工具；修复后普通会话与管理员会话均断言不包含 `batchEditPictures` / `batchUploadByUrl`；
- 聚焦 agent 8 例通过；提交门禁 backend 77 例、`ai/agent` 186 例通过。

## 3. 日志与异常脱敏

### 改动

- `MyLoggerAdvisor` 的 INFO 只记录请求开始、响应收到，不记录完整 Prompt 或模型回答；
- `ToolCallAgent` 的 INFO 只记录工具名和数量，不记录 arguments、responseData 或 `askHuman` 的完整问题；
- `HuoshanToolSupport` 对未知异常固定向用户返回“调用图库接口失败，请稍后重试”，内部异常栈仍留在服务端日志。

### 先红后绿

- Logger 测试修复前可捕获完整 `SENSITIVE\nFORGED-LOG-LINE`，修复后原文不再进入 INFO；
- 工具异常测试修复前会把 `http://127.0.0.1:8123/internal-path` 返回用户，修复后只返回固定文案；
- 新增 `MyLoggerAdvisorTest`、`ToolCallAgentLoggingTest`、`HuoshanToolSupportTest`；与工具装配测试一起聚焦 8 例通过。

代码提交：`209b314 fix: 暂停助手写入并脱敏日志`。

## 4. 页面与功能口径同步

- 顶部明确“当前助手只提供查询和建议，不会直接修改图库”；
- 四条示例改为：`列出我的空间`、`看看某个空间里有哪些图片`、`看看这些图片适合哪些标签`、`帮我搜索一些图片素材`；
- 输入框聚焦空间、图片、标签和素材搜索；
- F11/F12 明确区分“档 3 历史实现过直接写入”与“当前首次公开部署暂时只读”。

验证：

- `npm run check:assistant-copy`：PASS；
- `npm run check:assistant-format`：PASS；
- `npx eslint src/pages/AssistantPage.vue scripts/check-assistant-copy.mjs`：0 错；
- type-check 文档门禁确认仍为存量 124 例，未净增；
- 提交门禁 backend 77 例、`ai/agent` 186 例通过。

代码提交：`91ede81 feat: 同步助手只读提示文案`。

## 未完成与边界

T27 整体**仍未完成**：

- SSE 早取消竞态尚未修；
- 2048 字符错误体的单行无界读取尚未修；
- T27.4～T27.7 的确定性写确认门尚未实现，因此两个写工具继续保持不装配；
- 已关闭的多行 SSE、缺 `[DONE]`、线程池拒绝空 500、300 秒超时、GET message 日志和频控/长度限制不在本轮重复处理。
