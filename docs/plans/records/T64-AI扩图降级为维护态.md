# T64 — AI 扩图降级为维护态

> 2026-10-16。字据/设计/验收全文见 `docs/designs/2026-10-16-AI扩图降级为维护态设计.md`，讲解见 `docs/features/F5-AI扩图.md`。
> 本文件只记实施过程、红绿输出、负向控制与真机证据。代码 commit `756df73`；字据 commit `7769d78`；批次结转 `036e7a8`。

## 范围

- 新增：`backend/domain/vo/VOutPaintingStatusVO.java`、`backend/src/test/java/.../service/impl/OutPaintingMaintenanceGateTest.java`（5 例，纯 Mockito 无 Spring）。
- 修改：`service/impl/PictureServiceImpl.java`（维护文案常量 + `app.outpainting.enabled` 注入 + 门闩 + 状态查询）、`service/IPictureService.java`（状态查询接口）、`controller/PictureController.java`（`GET /out_painting/status`）、`resources/application.yaml`（`enabled: ${OUTPAINTING_ENABLED:false}`）。
- 修改：`service/impl/OutPaintingTaskIdempotencyIntegrationTest.java`（补 `app.outpainting.enabled=true`——它证的是**开启态**的幂等与配额语义没被破坏）。
- 前端：`components/ImageOutPainting.vue`（开弹窗取状态、维护态显示 `a-alert` 并隐藏生成按钮）、`api/pictureController.ts`（手写状态接口）、`api/typings.d.ts`（两个类型）。
- 运维：`deploy/prod-checklist.md`（登记 `OUTPAINTING_ENABLED` + 2.2 节维护态说明与恢复路径）。
- **未改动**：`AliYunAiApi`、扩图请求/响应模型、幂等键与配额算法本身；不新增依赖 / 表 / 进程；不动 spec 关键三行。

## 实现要点

1. `checkOutPaintingAvailable()` 是 `createOutPaintingTask` 与 `getOutPaintingTask` 的**第一条语句**——先于图片存在性、权限、幂等占位与配额计数。关闭态因此不产生"这张图存在与否"的探测信号，也不产生任何百炼调用、Redis 键或配额计数。
2. 维护文案是 `PictureServiceImpl.OUT_PAINTING_MAINTENANCE_MESSAGE` 一个 `public static final` 常量，异常与状态端点**同源**（前端只展示后端下发的那句话，自己只留一个兜底串）。
3. 状态端点不加鉴权注解：只读、幂等、无副作用、不含用户数据；`/picture/**` 在 `SaTokenConfigure` 的注解可信区内，但未标注解的端点不会被 SaInterceptor 拦。
4. 前端取状态失败时**不显示维护横幅**（保持现有界面）：服务端门闩已兜住，宁可少一次误报"坏了"，也不要把能用的功能显示成"维护中"。

## 红绿输出（可复跑）

```
cd backend
mvn -B test -Dtest=OutPaintingMaintenanceGateTest -DfailIfNoTests=false
```

- **红（先写测试）**：第一轮是编译红（`VOutPaintingStatusVO` 不存在）。为让红落在**行为**而非编译上，先补 VO / 状态方法 / 配置、**故意不加两道门闩**，再跑：`Tests run: 5, Failures: 2` —— `closedGateBlocksCreateTaskBeforeAnythingElse`、`closedGateBlocksGetTask` 两例抛出的不是维护异常（断言「应抛 BusinessException 且文案＝维护文案」失败）；另外 3 例（开启态放行两例、状态 VO 一例）已绿。
- **绿（加门闩后）**：同命令 `Tests run: 5, Failures: 0`。
- **负向控制（规则 12）**：把门闩条件短路成 `if (!outPaintingEnabled && false)`（恒放行）→ 恰好那 2 例红；恢复即绿。
- **开启态语义不回归（需 MySQL 3306 + Redis 6379）**：
  - `mvn -B test -Dtest=OutPaintingTaskIdempotencyIntegrationTest` → `Tests run: 3, Failures: 0`（幂等复用、超额拒绝、AI 失败回滚三条语义在门闩开启时不变）。
  - **默认 false 的负向控制**：把该测试的 `app.outpainting.enabled=true` 去掉（即 `application.yaml` 的默认值生效）→ `Tests run: 3, Failures: 1, Errors: 2` 全红（`失败的创建应回滚额度计数 ==> expected: <0> but was: <null>` 等），证明"不配环境变量就是维护态"真的成立、且门闩拦在 Redis 之前。
- **门禁口径全量**：`mvn -B test -Dtest="!*IntegrationTest,!RedisStringTest,!YunPictureBaseApplicationTests"` → **174/174 绿**（本轮新增 5 例）。提交时 pre-commit 门禁（backend + `ai/agent` 引擎 215 例）全绿。
- **集成套件全量**（本机 MySQL 3306 + Redis 6379，COS 走 mock）：`mvn -B test -Dtest="*IntegrationTest,RedisStringTest,YunPictureBaseApplicationTests"` → **110/110 绿**，证明扩图门闩没有波及其它链路。
- **前端**：`npm run type-check` 0 错（基线 `errors=0` 未动）、`npx eslint .` 无输出、`check:assistant-format` / `check:assistant-copy` / `check:batch-crawl-result` 三项 PASS。

## 真机证据（2026-10-16，本机 8123 + 5173 + MySQL/Redis）

前置：`mvn -B package -DskipTests` 重新打包（配置烤在 jar 里），`java -jar`（JDK 17）起后端，`npm run dev` 起前端。**未配 `OUTPAINTING_ENABLED`**（＝取默认 false）。

1. **状态端点（无需登录）**：
   ```
   curl.exe -s http://localhost:8123/api/picture/out_painting/status
   {"code":0,"data":{"available":false,"message":"AI 扩图功能维护中：厂商扩图模型已下线，暂时无法生成，恢复后本页会自动重新开放"},"message":"ok"}
   ```
2. **登录态下服务端门闩真的拦**（浏览器会话内 `fetch`，带 cookie）：
   ```
   GET /api/picture/out_painting/get_task?taskId=probe-task-t64
   → {"code":50001,"data":null,"message":"AI 扩图功能维护中：厂商扩图模型已下线，暂时无法生成，恢复后本页会自动重新开放"}
   ```
   注意这是**维护文案而非"任务不存在/无权查看"**——说明拦在归属校验之前。
3. **零 Redis 痕迹**：探针前后各跑一次 `redis-cli.exe --scan --pattern "huoshantuku:outpainting:*"`，两次输出**均为空**（幂等键 `…:idempotent:*`、配额键 `…:quota:*`、归属键 `…:owner:*` 一个都没生成）；后端日志内搜 `out_painting` / `OutPainting` 亦无命中。
4. **界面（浏览器 DOM 快照，可复跑：登录 1 号普通用户 → `/add_picture?id=2102421103233830914` → 点「AI 扩图」）**：
   ```
   - dialog "AI 扩图":
     - button "Close"
     - generic: AI 扩图
     - alert:
       - img "exclamation-circle":
       - generic: AI 扩图功能维护中：厂商扩图模型已下线，暂时无法生成，恢复后本页会自动重新开放
     - heading "原始图片" [level=4]
     - img "猫2"
     - heading "扩图结果" [level=4]
   ```
   弹窗内**没有**「生成图片」按钮（只有 Close），维护通知文案与后端下发的一致。

### 一个观察（非本次改动）

同一账号直接 POST `create_task` 时先被 `@SaSpaceCheckPermission(PICTURE_EDIT)` 拦下（`40101 无此权限：picture:edit`，公共图库图对该账号没有编辑权限）——这是既有的注解层，与本次门闩无关；门闩在服务层，故换 `get_task`（无注解）做服务层验证。

## 已知限制 / 未覆盖

1. **维护态下 `get_task` 也不可用**：门闩同时拦查询，历史 taskId 无法再查（taskId 只存 Redis 1 天，且在途任务对应的模型已下线）。设计与理由见设计文档。
2. **"迁移到某模型后能否真的生成"没有也不可能有证据**：门闩关闭态下不存在厂商调用路径；恢复属另一个任务（需新字据）。
3. **前端无单测框架**：维护态展示的正确性只由上面第 4 条真机快照背书，没有自动化断言（若改动 `ImageOutPainting.vue` 的维护分支，需重跑真机）。
4. **`OUTPAINTING_ENABLED=true` 的界面路径未做真机验证**：开启态没有可用模型，开出来也只会得到"接口响应异常"；开启态的门闩放行与状态 VO 由单测覆盖。
