# T54 — 前端 API 类型对齐后端 Long→String

> 2026-10-13。口径与影响面见 `docs/decisions/2026-10-13-api-types-long-to-string.md`；本文件只记实施过程、红绿输出与证据。

## 起因

浏览器点开图片详情页报 `获取图片详情失败，请求数据不存在`；后端返回 `{"code":40400,"data":null,"message":"请求数据不存在"}`（HTTP 200）。请求 URL 为
`GET /api/picture/get/vo?id=2102421103233831000`，而库里该图真实 ID 是 `2102421103233830914`（"猫2"）。

## 根因

JS `Number` 只能安全表示 ≤ 2^53-1 的整数。19 位雪花 id 经 `Number()` 后被截断：

```
String(2102421103233830914) === "2102421103233831000"   // 末位丢失
```

`PictureDetailPage.vue` 的 `id: Number(props.id)` 是直接肇因；由 2026-10-12 的 `3bd7d77`（T53-B5）引入
（`git blame` 显示该行由 `id: props.id` 改成 `id: Number(props.id)`），动机是让 `vue-tsc` 通过——生成的
`typings.d.ts` 把 `id` 声明成了 `number`。同类 `Number(id)` 共 5 处（图片详情 / 图片编辑 / 空间详情 /
空间编辑 / 空间成员管理）。

后端侧无问题：`JsonConfig` 注册 `Long → ToStringSerializer`，Long 一律以字符串下发。

## 方案

1. **`frontend/src/api/typings.d.ts` 定向改写**：把恒为 int64 的字段由 `number` 改 `string`。
   - 判据（可复跑）：从 `http://localhost:8123/api/v2/api-docs` 取 `definitions`，
     ① 属性名在全 spec 内**恒为** `int64`（18 个：`id/userId/spaceId/pictureId/pictureIdList/fromUserId/reviewerId/targetId/maxCount/maxSize/size/pages/picSize/totalCount/totalSize/usedCount/usedSize/timeout`）→ 无条件改；
     ② 混合属性名（`data/current/total/count`）按**所属定义**判定（`Page«X»` 是 int64、`*QueryRequest`/`TaskMetrics`/`PictureUploadByBatchRequest` 是 int32），定义名经归一化（去 `«»` 与 `对象`、容错尾 `_`）匹配。
   - 结果：改写 **120 行**（number ↔ string），文件保持 CRLF。
2. **`frontend/openapi.config.js` 增加 `hook.customType`**：`format === 'int64'` → `'string'`，并显式处理**数组项**（`items.format === 'int64'` → `'string[]'`）——生成器 `defaultGetType` 走数组分支时直接递归调 `defaultGetType`、不再经过钩子，实测不显式处理则 `pictureIdList` 仍是 `number[]`。**仍不执行 `npm run openapi`**（会清空 `src/api/`、丢手写文件，见 2026-09-15-topic10）。
3. **删除因类型收紧而暴露的 `Number(id)` 强转**，并把沿链路的 prop / 参数类型由 `number` 改 `string`（`spaceId`、`pictureId`、删除句柄的 `id` 等）。

## 红绿输出（可复跑）

- **红**：只改 `typings.d.ts`（第 1 步）后 `npm run type-check` → **49 错 / 22 文件**，含 5 处 `Number(id)` 与分页 `total`、`formatSize(picSize)`、百分比算术。
- **绿**：逐处修完后 `npm run type-check` → **0 错**；`npx eslint .` → 0 输出；`check:assistant-format` / `check:assistant-copy` / `check:batch-crawl-result` → 三个 `RESULT: PASS`。
- **负向控制**：把 `PictureDetailPage.vue` 的 `id: props.id` 改回 `id: Number(props.id)` → `npm run type-check` 立刻报
  `src/pages/PictureDetailPage.vue(167,7): error TS2322: Type 'number' is not assignable to type 'string'`（1 错）；还原后回 0。证明"类型契约 + type-check 基线"这道锁真的会拦住回归。

> 锁的说明：本任务**不新增脚本**。id 声明为 `string` 后任何 `Number(id)` 都是编译错误，而 pre-commit 在暂存
> frontend 文件时由 `.githooks/check-docs-consistency.mjs` 核对 `frontend/type-check-baseline.txt`（`errors=0`），
> revert 类型或重新引入强转都会被拦下。

## 真机证据（不可复跑，证据已过期）

- 2026-10-13 本机浏览器（前端 5173 / 后端 8123）打开 `http://localhost:5173/picture/2102421103233830914`：
  图片正常加载，字段正确渲染（作者 `管理员-小霖`、名称 `猫2`、格式 `webp`、尺寸 `1024 × 819`、大小 `102.88 KB`、主色调 `0x447b7b`），**无 `40400` 提示**。复现步骤：登录任意账号 → 从图库列表点进任意图片详情。
- 本任务**未做**全站人工回归（上传/编辑/批量编辑/空间分析/管理页分页等只经类型检查与静态审查，未逐页点验）。

## 未做 / 边界

- **没有整体重生成 `src/api/`**：现有 typings 相对当前后端有约 464 行漂移；重新生成会产出拼音文件名并丢掉手写类型
  （`EmailCaptchaRequest` / `Space` / `SpaceCategoryAnalyzeResponse` 等，实测单独替换会带出 29 个错误）。漂移不在本任务范围。
- 只改类型声明与因类型收紧必须调整的调用点，不改运行时数据形状（后端下发的一直是字符串）。
