# T65–T67 补验证据复核与 T66 硬失败补验（2026-10-17）

> 用户要求修复 `da45721` 补验的五处证据/脚本缺陷。本批只改 `docs/evidence/` 验收脚本与证据、对应 plan / decisions / 同日 handoff / F12 验证指针，不改业务实现、依赖、确认门口径或 spec 三行。

## 原报告复核结论

五项证据/脚本缺口本批现在修，原输出的有效范围与逐处勘误只在 `docs/evidence/2026-10-17-real-model-browser-e2e.md` 维护。本记录只落新增真机证据、永久脚本红绿与可复跑命令，不把未覆盖的故障注入/模型随机性改写成“全部通过”，不扩展业务实现或新增任务。

## 真机补验环境与安全边界

- 使用本地 backend 8123、引擎 8124、MySQL 3306、Redis 6379 DB 1。会话开始时两个应用均未监听，本批以 local profile 隐藏启动现存 jar；manager/controller/门卫三个 jar class 与当前 target/classes SHA-256 一致，未重建或改生产代码。
- 直接调用真实 prepare/confirm API，不走模型或浏览器，不调用 COS。不能将这次确定性集成补验称为新一次真实模型端到端验收。
- 脚本预查未使用的空间 ID `9223372036854775806` 与图片 ID `9223372036854775805`，即使是软删行也必须为 0。prepare 只校验形状；正确确认在已消费计划后的 service 空间存在性检查抛出原 `40400 / 空间不存在`，在业务写入前失败。
- 错误 chatId 使用真实 operationId 和正确 digest；Redis HGETALL 前后完全一致，正确/错误两个派生会话均为 0 行。随后正确 chatId 确认，计划消费、原业务错误不变，正确会话恰好一条 `USER / system_fact / batch_edit / failed`。重放拒绝后原事实完全不变，不新增第二条。
- 只新增保留一条失败事实消息；没有创建、改写、删除图片/空间，没有删除任何用户文件或测试数据。不清理该事实，避免引入清理误删风险。

## 复跑

在仓库根 PowerShell 中执行（口令只由环境变量提供，不把真实值写进命令记录）：

```powershell
# 前置：本地两个应用、MySQL、Redis 已启动；mysql/redis-cli 在 PATH。
# 由调用者设置 HS_TEST_PASSWORD（测试账号）和 MYSQL_PWD（只读查询所需 DB 账号口令）。
# 默认账号 123456；可用 -Account / -MysqlUser / -MysqlHost / -MysqlPort 调整。
powershell -NoProfile -File docs/evidence/2026-10-17-assistant-e2e-hard-failure.ps1 -SelfTest
powershell -NoProfile -File docs/evidence/2026-10-17-assistant-e2e-hard-failure.ps1 -OutFile "$env:TEMP/t66-hard-failure.txt"
```

脚本源码 ASCII / UTF-8 无 BOM，输出文件显式 UTF-8；需要 mysql CLI，但不新增运行时依赖。SQL 全部只读；任一步断言失败抛错、退出非零，并在 finally 保存已经取得的输出。SelfTest 无网络、无 DB，包含合法失败事实、Java UUID 固定向量和 6 个负向控制（意外成功、错误消息、无事实、重复事实、executed、错操作身份）。

## 本次真实输出

以下为本次脚本生成的 UTF-8 文本，直接落定，不以临时目录作为唯一证据：

```text
PREFLIGHT sentinel space=0 picture=0; no business rows will be created
LOGIN code=0 userId=2045072064452816897
CHATID fc4a0fd2-0782-4fba-b329-f91286c2430f CONVERSATIONID e9cc8186-4656-39a6-b1ae-e03469085cc5
WRONG_CHATID 42928f70-85c2-4759-935f-2f18f0cf1d92 CONVERSATIONID be64dd42-43ce-3779-876c-48412bb725dd
PREPARE code=0 operationId=7db1cdd3ae7632a32a00a0d176aab9b8 type=batch_edit facts=0
WRONG_CHAT code=40400 plan=unchanged facts=0
CONFIRM code=40400 message=空间不存在 plan=consumed facts=1 outcome=failed
FACT {"content":"【系统记录】用户确认的写操作执行失败：空间不存在。[操作 7db1cdd3ae7632a32a00a0d176aab9b8]","metadata":"{\"operationId\":\"7db1cdd3ae7632a32a00a0d176aab9b8\",\"operationType\":\"batch_edit\",\"huoshanSource\":\"system_fact\",\"messageType\":\"USER\",\"outcome\":\"failed\"}","messageType":"USER"}
REPLAY code=40400 facts=1 original-fact=unchanged
PASS: authenticated hard failure, wrong-chat rejection and replay verified against real Redis + engine MySQL
```

## 会话 SQL 的正向控制

同一条实际存在的失败事实，独立 mysql CLI 只读复查：

```sql
SELECT COUNT(*) FROM huoshan_ai_agent.chat_message
 WHERE conversation_id='fc4a0fd2-0782-4fba-b329-f91286c2430f'; -- 原始 chatId，错误查询
SELECT COUNT(*) FROM huoshan_ai_agent.chat_message
 WHERE conversation_id='e9cc8186-4656-39a6-b1ae-e03469085cc5'; -- 正确派生 ID
SELECT COUNT(*) FROM huoshan_ai_agent.chat_message
 WHERE conversation_id='be64dd42-43ce-3779-876c-48412bb725dd'; -- 错误 chat 的派生 ID
```

```text
raw_chatId                  0
derived_conversation_id     1
wrong_chat_derived_id       0
mysql exit_code             0
```

这组「存在一条消息」的正向控制证明原始 chatId 查询会假阴性；不是用另一组空会话的 0 行循环论证映射正确。

## 脚本红绿与其余未认证输入

永久离线回归在 PS 5.1 中运行三个真实脚本的 AST 对应源码，只替换 HTTP/Redis/文件 IO 边界，不连接服务，不新增依赖：

```powershell
powershell -NoProfile -File docs/evidence/2026-10-17-assistant-e2e-scripts-test.ps1
```

| 阶段 | 输出 | 说明 |
|---|---|---|
| 修复前完整基线 | passed=5 failed=27 total=32 | 登录/响应码/缺确认事件/流结束/参数编码/派生 SQL 等真实源码行为红灯 |
| 参数收敛与真实协议夹具更新时 | passed=26 failed=6 total=32 | 移除真实计划输入；逐项结果必须用实际 `items[].ok`，不能让错误夹具替生产协议背书 |
| UTF-8 拒绝响应新增用例，修复前 | passed=32 failed=1 total=33 | 真实复跑暴露 PS5.1 在无 charset 响应下解码错误；先补离线原始字节回归 |
| 最终主线程独立运行 | passed=33 failed=0 total=33 | 保留全部测试，无删除或跳过 |

最终原样摘要：

```text
OFFLINE RESULT passed=33 failed=0 total=33
SELFTEST PASS: valid failure + UUID vector + 6 negative controls
```

确认脚本默认要求全成功；预期 URL 逐条失败时必须显式传 `-ExpectedRequestedCount 1 -ExpectedSucceededCount 0 -ExpectedFailedCount 1 -ExpectedSuccess false`。结果身份/计数/`items[].ok` 不一致、缺唯一确认事件、EOF 缺 DONE、error 帧、HTTP 异常、非预期业务码均抛错，不能只打印后正常结束。JSON 使用对象序列化，消息按整体 UTF-8 编码；probe 响应显式 UTF-8 解码，中文不再误记成 Latin-1。

### 最终三类非法输入复跑

执行原报告同一 probe 命令，未运行模型；默认只发送不存在 operationId、含真实 LF 的 operationId、空字段三项。真实绑定/重放明确标作由 hard-failure 脚本独立覆盖，不接受可能误消费的真实计划文件。以下是修订脚本最终 UTF-8 日志：

```text
LOGIN code=0 userId=2045072064452816897
CONVERSATION userId=2045072064452816897 chatId=5d7c8b05-4318-4a1d-a0cf-6ba7d02eaf20 engineConversationId=27e8d6ec-4efe-3bcb-8485-b7dbb3d04269
SQL SELECT COUNT(*) AS rows_total, COALESCE(SUM(metadata LIKE '%system_fact%'),0) AS facts FROM chat_message WHERE conversation_id = '27e8d6ec-4efe-3bcb-8485-b7dbb3d04269';
SQL SELECT id, metadata FROM chat_message WHERE conversation_id = '27e8d6ec-4efe-3bcb-8485-b7dbb3d04269' AND metadata LIKE '%system_fact%';
CASE|nonexistent-opid|code=40400|msg=操作不存在、已过期或已处理
CONVERSATION userId=2045072064452816897 chatId=43b7f86d-e441-4af4-a2ec-c2cf68564bbf engineConversationId=f3d41f4c-51a5-3909-b964-e46914c92c37
SQL SELECT COUNT(*) AS rows_total, COALESCE(SUM(metadata LIKE '%system_fact%'),0) AS facts FROM chat_message WHERE conversation_id = 'f3d41f4c-51a5-3909-b964-e46914c92c37';
SQL SELECT id, metadata FROM chat_message WHERE conversation_id = 'f3d41f4c-51a5-3909-b964-e46914c92c37' AND metadata LIKE '%system_fact%';
CASE|text-with-newline|code=40400|msg=操作不存在、已过期或已处理
CONVERSATION userId=2045072064452816897 chatId=2e5587f3-00ac-429a-ba12-c365dbc46aad engineConversationId=4dde7a7c-10c2-32b0-a3a6-a6886dcefbc7
SQL SELECT COUNT(*) AS rows_total, COALESCE(SUM(metadata LIKE '%system_fact%'),0) AS facts FROM chat_message WHERE conversation_id = '4dde7a7c-10c2-32b0-a3a6-a6886dcefbc7';
SQL SELECT id, metadata FROM chat_message WHERE conversation_id = '4dde7a7c-10c2-32b0-a3a6-a6886dcefbc7' AND metadata LIKE '%system_fact%';
CASE|empty-fields|code=40400|msg=操作不存在、已过期或已处理
SKIP|binding-wrong-chatid|covered separately by 2026-10-17-assistant-e2e-hard-failure.ps1
SKIP|consumed-replay|covered separately by 2026-10-17-assistant-e2e-hard-failure.ps1
PROBE rejection codes verified; DB absence must be checked separately using engine IDs above.
```

实际执行上面每条派生会话 SQL，并在探针前后查全库 system_fact 数；原样结果：

```text
probe_exit_code=0
system_fact_before=6
conversation_id=27e8d6ec-4efe-3bcb-8485-b7dbb3d04269 rows=0
conversation_id=f3d41f4c-51a5-3909-b964-e46914c92c37 rows=0
conversation_id=4dde7a7c-10c2-32b0-a3a6-a6886dcefbc7 rows=0
system_fact_after=6
SQL ASSERTIONS PASS: three derived conversations empty; global count unchanged
```

其中 6 是当时全库已有事实数（包括本批新增的 1 条失败事实），不是六次新写入。会话级 0 行为主要证据，全库计数仅作同一无并发测试窗口的辅助观察。复跑必须在引擎数据库执行脚本打印的 SQL，不能把探针正常退出等同于已完成 DB 核对。

新增 hard-failure 脚本另经独立 Subagent 有界只读复核与 SelfTest，未发现 P1/P2；该复核未独立连接真机。三个既有脚本由主线程对照真实响应协议检查，并独立运行最终离线回归与真实 probe。


## 未验证与环境残留

- 未重新运行浏览器或调用真实模型；原浏览器叙述仅保留为上一轮历史观察。没有新截图；临时运行日志不作为唯一证据。
- T65「写成功 + 摘要补读失败」、T67 特定格式否定 token 的真实模型原始输出、T62 历史窗口的真实模型行为仍未真机覆盖；既有永久单测不改、不跳过。
- 本次真实补验的 operationId / 两个 chatId / 两个派生 conversation_id 已随上述原始输出固定；失败事实保留。Redis 操作计划已被正常确认消费，验证码/登录会话按应用 TTL 管理。

- 本批自行启动的 backend PID 31612 / 引擎 PID 50044 已在验证结束后核对进程身份并停止；8123/8124 恢复为本批开始时的无监听状态。MySQL / Redis / 前端未启停；未部署或推送。
