# 2026-09-29 写确认门本地全链路冒烟（T27.9 真机验收）

> 目的：补上 T27.9 的**真机验收项**（前端确认请求的时限与失败文案只在浏览器里才真正生效），并为确认门全程留一份可复跑的探针。
> 环境：本机 backend 8123（`local,test`）+ 引擎 8124（`local`）+ 前端 vite 5173；本机 MySQL/Redis；MCP 8127 未启（顺带复现存量的降级 notice）。
> 结果：**通过**——两次真实确认分别耗时 11743ms / 14235ms（均 > 前端旧的 10s 超时），卡片如实显示"入库完成：成功 6 / 6 条"，没有误报超时。
> 冒烟数据已清零（两侧库 user/space/picture/ai_conversation/chat_message/chat_summary/conversation 全 0），图片走应用删除接口清理（**COS 对象同删，无孤儿对象**）。

## 复现步骤（脚本部分，可复跑）

1. 起服务：后端 `java -jar backend/target/yun-picture-base-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,test --app.ai.assistant.internal-api-key=<一次性随机值>`；引擎 `set HUOSHAN_HEADLESS_API_KEY=<同一个值> & java -jar ai/agent/target/my-ai-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`；前端 `cd frontend && npm run dev`。
2. 造冒烟账号与专属空间（历史 MD5 口径口令：`md5('asdewqzzxcc' + 口令)`，本机库执行）：
   ```sql
   insert into user (userAccount, email, userPassword, userName, userRole)
   values ('smoke-t279', 'smoke-t279@example.com', '<md5>', 'smoke-t279', 'user');
   set @uid = (select id from user where userAccount = 'smoke-t279');
   insert into space (spaceName, spaceLevel, maxSize, maxCount, totalSize, totalCount, userId, spaceType)
   values ('smoke-t279-space', 0, 104857600, 100, 0, 0, @uid, 0);
   ```
3. 跑探针（Node ≥ 20；读同目录凭据文件 `{account,password,userId,spaceId}`，不落任何明文到输出）：核心四步 `POST /api/user/login` → `POST /api/ai/assistant/ticket` → `GET /api/ai/assistant/chat?message=…&chatId=…&ticket=…`（读 SSE 直到 `[DONE]`，取 `confirmation_required` 帧）→ `POST /api/ai/assistant/operations/confirm`（**计时**），再 `POST /api/picture/list/page/vo` 核对落库。
4. 浏览器路径：登录 → `/assistant` → 贴 6 条 `https://picsum.photos/id/<id>/1600/1200.jpg` 直链 + 目标空间 → 卡片出现后点"确认执行" → 观察卡片状态（`确认中` → 结果行）。

## 实测输出（2026-09-29，原文节选）

**脚本探针（6 条直链，1200×1600 jpg）**

```
[login] http=200 code=0 cookies=2
[ticket] http=200 code=0 ticketLen=32
[chat] frames=8 elapsedMs=50338
  frame: {"event":"notice","content":"图片搜索服务暂时不可用，本轮对话无法帮你搜图（其它能力不受影响）"}
  frame: {"event":"step","kind":"tool","name":"listSpaces",...}
  frame: {"event":"confirmation_required","operationType":"url_import","operationId":"8344c0c2…","digest":"f775cbdc…","summary":["目标空间 id：2102416518058352643","入库 URL：6 条"]}
  frame: {"event":"answer","content":"导入计划已生成，等待你确认：…目前尚未改动任何数据。…"}
[confirm] http=200 code=0 elapsedMs=11743
[confirm] body={"operationType":"url_import","success":true,"requestedCount":6,"succeededCount":6,"failedCount":0,...}
[verify] pictures in space=6   （全部 format=webp，落在腾讯云 COS）
```

**浏览器（同一套后端/引擎，第二条消息；`chatId=61818e59-…`）**

```
卡片：待确认：按 URL 入库 / 目标空间 id：2102416518058352643 / 入库 URL：6 条 / 确认执行 / 取 消
点击"确认执行"后 ~2s 卡片进入"确认中"，14235ms 后渲染结果行：
入库完成：成功 6 / 6 条
（无"确认未完成"、无 timeout 文案）
空间核对：12 张（两次各 6 张），全部 webp + COS 地址；全部经 /picture/delete 清理后空间归 0
```

**服务端日志旁证**

```
17:01:00 AiAssistantController  AI 助手对话开始, userId=…, chatId=smoke-t279-1790672459260
17:01:29 AiAssistantOperationManager  AI 助手写操作待确认, operationId=8344c0c2…, type=url_import, entries=6
17:07:02 AiAssistantController  AI 助手对话开始, chatId=8f0d74c2-…（浏览器第 1 次；页面中途被 IAB 面板重载，SSE 中断）
17:07:36 AiAssistantProxyManager  AI 助手转发失败: java.io.IOException: 你的主机中的软件中止了一个已建立的连接。（客户端已断开 → 网关按既有路径收尾，无重复请求）
17:08:48 AiAssistantController  AI 助手对话开始, chatId=61818e59-…（浏览器第 2 次，卡片与确认都在这条会话上完成）
```

## 不可复跑 / 环境说明

- **入库探针脚本**：`docs/evidence/2026-09-29-confirm-gate-probe.mjs`（`node <脚本> <凭据文件> [条数]`）。它由上文脚本探针整理而成，并在写档后**又实跑两次自验**（另一个同名冒烟账号）：2 条 → confirm 5281ms、2/2 入库、核对 2 张；1 条 → confirm 3319ms、1/1 入库、核对 1 张；两次的图片同样走应用删除接口清理。
- 浏览器的交互取证（卡片出现 → 点击 → 结果行）是**一次性探针**：ZCode 内置浏览器面板在长等待时会重载页面（表现为 `performance.timeOrigin` 变化、SSE 被中断），所以卡片只在该次会话里存在；复现请按上面的浏览器路径手工走一遍。
- 本机 Redis/MySQL/COS 都是真实依赖：`confirm` 的耗时由**外网下载速度 + COS 转存**决定，换网络环境数字会变（这正是要点：它随时可能越过 10s，客户端时限必须放开通到服务端）。
- 引擎侧 chat 消息、会话摘要、会话行已按确定性 conversationId（`uuid5("huoshan:<userId>:<chatId>")`）删除；库里不留任何冒烟痕迹。
