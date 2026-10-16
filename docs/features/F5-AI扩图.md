# F5 AI 扩图（阿里百炼）

> 覆盖任务：docs/plan.md T64（降级为维护态；此前为 T1 批次存量功能）。
> 口径：docs/decisions.md 2026-10-16「AI 扩图降级为维护态」；设计与理由全文见 `docs/designs/2026-10-16-AI扩图降级为维护态设计.md`。

## 一句话

图库里把一张图的画面向外扩展（阿里百炼 `image-out-painting`）：创建任务 → 轮询结果 → 把扩图结果作为新图上传回原图所在空间。**2026-10-16 起该功能降级为维护态**——功能代码保留、默认关闭（`app.outpainting.enabled=false`），界面上只显示维护通知。

## 怎么用

- **用户侧（维护态，默认）**：打开图片的「AI 扩图」弹窗，看到维护通知（`a-alert`），**没有「生成图片」按钮**，因此不可能发起任务。
- **管理员恢复时**：确认已选定可用的图像模型并改好 `AliYunAiApi` 的端点与请求体（属另一个任务，见下「恢复路径」），然后配 `OUTPAINTING_ENABLED=true` 重启 backend，**前端无需重新发版**——弹窗打开时会请求状态端点，`available=true` 即恢复出「生成图片」按钮。
- **运维**：`app.outpainting.daily-quota`（默认 20 次/人/日）仍在，恢复后继续生效。

| 端点 | 说明 |
|---|---|
| `POST /api/picture/out_painting/create_task` | 创建扩图任务（幂等 + 配额；`@RateLimit` 10 次/60s；`@SaSpaceCheckPermission(PICTURE_EDIT)`） |
| `GET /api/picture/out_painting/get_task` | 查询任务结果（按归属记录校验；`?taskId=`） |
| `GET /api/picture/out_painting/status` | **T64 新增**：只读维护状态 `{ available, message }`，无鉴权注解、无副作用、不含用户数据 |

## 怎么实现

### 关键文件

| 层 | 文件 | 职责 |
|---|---|---|
| 厂商适配 | `api/aliyunai/AliYunAiApi.java` | 创建任务（异步 `X-DashScope-Async: enable`）与查询任务，透传 traceId |
| 厂商模型 | `api/aliyunai/model/CreateOutPaintingTask*.java`、`GetOutPaintingTaskResponse.java` | 请求/响应结构（`input.imageUrl` + `parameters.xScale/yScale` 等） |
| 服务 | `service/impl/PictureServiceImpl.java` | **维护门闩** + 幂等占位 + 每日配额 + 归属记录 + 结果查询 |
| 接口 | `service/IPictureService.java`、`controller/PictureController.java` | 两个扩图端点 + 维护状态端点 |
| 配置 | `resources/application.yaml` | `app.outpainting.enabled`（默认 false）、`app.outpainting.daily-quota`（默认 20） |
| 前端 | `components/ImageOutPainting.vue` | 打开弹窗取状态；维护态显示通知并隐藏「生成图片」 |
| 前端 API | `api/pictureController.ts`、`api/typings.d.ts` | 手写三个接口与 VO 类型（**不跑生成器**，见 decisions 2026-09-15-topic10） |

### 核心流程

```
创建任务（维护态在此终止）
  门闩关闭？ → 抛维护提示（先于图片存在性/权限/配额/Redis，不产生任何探测信号与计费风险）
  查图片 + checkPictureAuth
  幂等键 = huoshantuku:outpainting:idempotent:<userId>:<pictureId>:<参数 md5>
     setIfAbsent(PENDING, 1d) 失败 → 命中：PENDING 则报"创建中"，否则复用已有 taskId（不扣配额）
  配额 = huoshantuku:outpainting:quota:<userId>:<yyyy-MM-dd>，INCR 首次设当天 24 点过期；超额度抛业务异常
  调百炼创建任务 → 幂等键改写为 taskId + 写归属键 huoshantuku:outpainting:owner:<taskId>
  任何 RuntimeException → CAS 释放占位（只删 PENDING）+ Lua 回滚配额（避免跨天 key 过期后造出 -1）

查询任务
  门闩关闭？ → 同样抛维护提示
  查归属键（缺失/非本人 → 拒绝）→ 调百炼查询
```

### 关键设计与理由

1. **门闩放在方法第一行**：关闭态下"零百炼调用、零 Redis 触碰"，且不给调用方"这张图存在/不存在"的探测信号；默认 `false` 是失败安全——不会因为漏配环境变量而开始按量计费。
2. **默认 `false` 而不是 `true`**：默认值即部署后的行为，失败安全优先。
3. **加状态端点而不是前端写死文案**：写死意味着恢复要前端重新构建发版；走端点则恢复只是后端配置变更，且维护文案前后端同源（后端常量）。
4. **不删扩图代码**：`AliYunAiApi`、幂等与配额逻辑原样保留，恢复时改的是配置不是代码。
5. **幂等键先占位再调接口**：同用户 + 同图 + 同参数只产生一个付费任务；失败路径释放占位并回滚配额，用户可以真正重试。
6. **归属记录（TTL 1 天）**：任务结果接口按归属校验，避免按 taskId 遍历他人任务；TTL 与任务生命周期相当。

## 怎么验证

- **维护门闩（T64，纯 Mockito 无 Spring）**：`OutPaintingMaintenanceGateTest` 5 例——关闭时 `createOutPaintingTask` 抛维护文案且**零查图、零百炼调用、零 Redis 触碰**；关闭时 `getOutPaintingTask` 同样被拦；开启时门闩不拦（走到后续校验）；状态 VO 两态。先红（5 跑 2 红）后绿 5/5；负向控制（门闩恒放行）恰好那 2 例红。
- **开启态语义不回归**：`OutPaintingTaskIdempotencyIntegrationTest`（需 MySQL/Redis）已补 `app.outpainting.enabled=true`，3/3 绿继续钉住幂等复用、超额拒绝与失败回滚；**把它去掉（即用默认 false）3 例全红**，证明"不配环境变量就是维护态"。
- **门禁**：后端单测口径 174/174 绿（本轮 +5）；前端 `type-check` 0 错、`npx eslint .` 无输出、`check:assistant-*` 与 `check:batch-crawl-result` 三项 PASS。
- **真机**（本机 8123 + 5173）：`GET /out_painting/status` 返回 `available=false` + 维护文案；登录态下 `GET /out_painting/get_task` 回同文案（证明拦在归属校验之前）；探针前后 `huoshantuku:outpainting:*` 键均为空；浏览器点开弹窗只有维护 `a-alert`、**没有「生成图片」按钮**。红绿数字与完整证据见 `docs/plans/records/T64-AI扩图降级为维护态.md`。

### 前端状态确认（T64 review 修复）

弹窗每次打开先进入加载态，只在状态接口成功且明确返回 `available === true` 后显示「生成图片」。维护态展示后端通知；网络失败、业务失败或缺失状态字段展示“暂时无法确认 AI 扩图状态，请关闭后重试”，不开放生成入口。关闭弹窗使旧请求失效，迟到响应不能覆盖重开后的状态；任务创建函数本身也检查可用状态。

常驻回归：`cd frontend` 后运行 `npm run check:outpainting-status`。脚本编译并执行真实 `ImageOutPainting.vue` setup，只替换网络和通知边界，覆盖加载中、维护、失败、异常响应、明确可用和乱序响应。红绿与浏览器检查边界见 `docs/plans/records/T64-补记-前端状态未知时关闭入口.md`。

## 已知限制

1. **维护态下 `get_task` 也不可用**：门闩同时拦查询。没有可用创建入口就不会有新任务，在途任务对应的模型已下线、查了也是失败；代价是历史 taskId 无法再查询——可接受（taskId 只存 Redis，TTL 1 天）。**影响面：低**；复现成本：低。
2. **维护文案是后端常量、不是可配置项**：改文案要改代码。选它是为了"同一句话只有一个来源"；将来需要按环境说不同的话再提取为配置。
3. **维护态下"能否真的生成"没有证据**：门闩关闭即不存在厂商调用路径，故本次不验证真实厂商调用——这也正是把"恢复"另立任务的原因（见下）。
4. **前端无专用单测框架**：状态判断由常驻脚本执行真实组件 setup 覆盖；浏览器展示另用真实组件 + 状态接口替身检查，不代表厂商端到端验收（见下方 T64 补记）。
5. **厂商侧依赖是不可控前提**：本次降级本身源于厂商下线（2026-10-10 生效）与替代模型免费额度作废，说明"外部模型长期可用"不成立；再次恢复前须重新核对模型可用性、地域与计费。

## 恢复路径（将来要做时）

1. 选定模型并改造 `AliYunAiApi` 的端点与请求体（例如 `wanx2.1-imageedit` 的 `function=expand` 只有扩展倍数参数、仅北京地域、按量 0.14 元/张）——**这是另一个任务，需要新字据**；
2. 配 `OUTPAINTING_ENABLED=true` 重启 backend；
3. 前端零改动：状态端点自动回到 `available=true`，「生成图片」按钮重新出现。
