# 前端 API 类型对齐后端 Long→String（T54）

> 2026-10-13 立。起因 = 图片详情页报 `40400 请求数据不存在`；根因是前端把 19 位雪花 id 用 `Number()` 转成 number，超出 JS 安全整数（2^53-1）后被截断。
> **口径以 `docs/decisions.md` 表内那一行为准**，本文件保留完整理由与证据。

## 背景：这条口径其实早就定了，是执行漏了一处

2026-09-14 已定「工具返回与入参的 id 一律按字符串透传」，后端 `JsonConfig` 注册 `Long → ToStringSerializer`，所有 Long 字段（含全部 id）都以**字符串**下发——正是为防 JS 精度丢失。
2026-09-15-topic10（前端 API 客户端不跑生成器）同时写明：「往 `typings.d.ts` 补类型时，**雪花 id 按 `string` 声明**」。

也就是说：**口径不缺，缺的是把 `typings.d.ts` 里那些当年按 `number` 生成/写下的字段对齐过来**。2026-10-12 的 T53（前端存量报错清零）在收口 `vue-tsc` 时，为了满足"生成类型里 `id?: number`"的既有声明，把 `PictureDetailPage.vue` 等处的 `id: props.id` 改成了 `id: Number(props.id)`——类型过了、运行时坏了。同类 `Number(id)` 共 5 处（图片详情 / 图片编辑 / 空间详情 / 空间编辑 / 空间成员管理）。

## 本次决定

1. **把 `frontend/src/api/typings.d.ts` 里恒为 int64 的字段类型由 `number` 改 `string`**，落实 2026-09-14 与 topic10 的既定口径。
2. **生成器 `openapi.config.js` 增加 `hook.customType`**（`int64 → 'string'`），使"将来若真要恢复生成器"时产出的类型天然正确；**但维持 topic10 的"不自动跑生成器"不变**（`npm run openapi` 仍不得执行——它会 rimraf 整个 `src/api/`）。
3. **不引入新脚本锁**：锁就是类型契约本身——id 声明成 `string` 后，任何 `Number(id)` 都会变成 `type-check` 错误，而 pre-commit 在暂存 frontend 文件时核对 `frontend/type-check-baseline.txt`（`errors=0`），revert 类型即被拦下。

## 影响面（实测）

- 后端 spec（`/api/v2/api-docs`）里 int64 字段 109 个；恒为 int64 的**属性名** 18 个（`id/userId/spaceId/pictureId/pictureIdList/fromUserId/reviewerId/targetId/maxCount/maxSize/size/pages/picSize/totalCount/totalSize/usedCount/usedSize/timeout`）；混合的 4 个（`data/current/total/count`）按"所属定义"逐个判定（`Page«X»` 是 int64，`*QueryRequest` 是 int32）。
- 定向改写 `typings.d.ts` **120 行**（number ↔ string）。
- 类型收紧后 `type-check` 暴露 **49 个错误、22 个文件**（这是"红"）：多为把长整型当 number 用（分页 `total` 赋值、`formatSize(picSize)`、百分比的算术）与 `Number(id)` 强转本身。
- 逐处修正后 `type-check` 回到 **0**（这是"绿"）。

## 与既有决定的关系

- **不推翻** 2026-09-14「id 按字符串透传」——是落实它。
- **不推翻** 2026-09-15-topic10「前端不跑生成器」——`npm run openapi` 仍不执行；只补了官方 `hook.customType`，与 topic10 提到的 `hookCustomFileNames` 同族，为将来"生成器 + 独立输出目录"那条路保留正确类型。

## 未做 / 边界

- **没有整体重生成 `src/api/`**：现有 typings 相对当前后端有约 464 行漂移，且重新生成会产出拼音文件名并丢掉手写类型（`EmailCaptchaRequest` / `Space` / `SpaceCategoryAnalyzeResponse` 等，实测单独替换会带出 29 个错误）；这些漂移不在本任务范围。
- 只改**类型声明**与因类型收紧必须调整的调用点，不改任何运行时数据的形状（后端下发的一直是字符串）。
