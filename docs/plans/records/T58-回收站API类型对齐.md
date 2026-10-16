# T58 — 回收站 API 的类型对齐（Long→String）与统一大小格式化

> 2026-10-16。来自两日改动 review 修复批次（plan.md T58，P2）；功能讲解同步在 `docs/features/F17-回收站.md`。
> 本文件只记实施过程、红绿/负向控制与证据。代码 commit `20876f5`。

## 范围

- 修改：frontend `src/api/typings.d.ts`（新增 7 个类型）、`src/api/pictureController.ts`（三个接口去 `request<any>`，请求体改用命名类型）、
  `src/pages/RecycleBinPage.vue`（删掉本地 `TrashItem`/`TrashOpResult` 与本地 `formatSize`）、`package.json`（新增 `check:format-size` 脚本）。
- 新增：`frontend/scripts/check-format-size.mjs`（`formatSize` 常驻断言，10 条）。
- **未跑** OpenAPI 覆盖生成（decisions 2026-09-15-topic10 不变）；backend 零改动。

## 改前是什么样

- 三个接口都是 `request<any>`：`listTrashUsingPost` / `restorePicturesUsingPost` / `purgePicturesUsingPost`。
  类型信息因此在页面里**又被声明了一份**（`interface TrashItem { id: string; picSize?: number ... }`、`TrashOpResult`）——
  两份定义各自演化，正是 T54 想消灭的漂移面；而且页面那份把 `picSize` 写成 `number`，与后端实际下发的字符串不符。
- 页面还自带一份 `formatSize`（KB 保留 1 位、缺值显示 `-`），与 `src/utils/index.ts` 的统一实现
  （KB/MB 保留 2 位、缺值 `未知`、0 字节 `0 B`）**两套口径**。

## 改了什么

1. typings.d.ts 按既有字母序新增：`PictureTrashQueryRequest`、`PictureTrashBatchRequest`、`PictureTrashVO`、
   `PictureTrashResultVO`、`PagePictureTrashVO_`、`BaseResponsePagePictureTrashVO_`、`BaseResponseListPictureTrashResultVO_`；
   分页的 `current/pages/size/total` 与 `id/spaceId/picSize/pictureId` 一律 `string`（T54 口径）。
2. 三个接口挂上新类型；请求体由内联字面量改为命名类型。
3. 页面：`type TrashItem = API.PictureTrashVO`、`type TrashOpResult = API.PictureTrashResultVO`（保留短别名以免 diff 噪音），
   `reportResults` 形参放宽为 `…[] | undefined`，删本地 `formatSize` 改用 `@/utils` 的统一实现。
4. 新增 `npm run check:format-size`（不进 CI 与 pre-commit，与既有三个 check 脚本同待遇）。

## 验证（可复跑）

```
cd frontend && npm run check:format-size && npm run type-check && npx eslint . \
  && npm run check:assistant-format && npm run check:assistant-copy && npm run check:batch-crawl-result
```

- **全绿**：`check:format-size` PASS（10 条）、`type-check` 0 错（基线 `errors=0`）、`npx eslint .` 无输出、另三项 check PASS。
- **负向控制 ①（错误类型，正对"19 位 id 原样透传"）**：把页面恢复调用的 id 注入 `number` 化
  （`pictureIdList: ids.map(Number)`）→ `type-check` 恰好 1 错：
  `src/pages/RecycleBinPage.vue(151,50): error TS2322: Type 'number[]' is not assignable to type 'string[]'.`
- **负向控制 ②（0 字节）**：把 `formatSize` 的守卫退回 `if (!size) return '未知'` → `check:format-size` 红：
  `FAIL 0 (number) is not "unknown" / expected: "0 B" / actual: "未知"`（正是 T54 修过的 `c977de3` 那个 bug）。

## 真机证据（2026-10-16，本机 8123 + 5173，登录 1 号普通用户）

夹具：该账号在公共图库没有自己的图、也不拥有任何空间，故用**页面上下文 multipart 上传**一张 72 B 的 1×1 PNG
（本机直连外网不通，URL 上传不可用；IAB 不支持文件选择框）。

1. 上传回包：`{code:0, id:"2111027283732324354", picSize:"72"}`——**picSize 是字符串**，正是 typings 声明的形状。
2. 软删（`POST /picture/delete`）后打开 `/recycle_bin`，行渲染为：
   `t58-probe | 公共图库 | 72 B | 2026/10/16 17:31:40 | 恢复 彻底删除`（`72 B` 走的是统一 `formatSize`，字符串入参被正确解析）。
3. 点行内「恢复」→ 提示 **「已恢复 1 张」**、表格回到「暂无数据」；随后该图以**原 id** `2111027283732324354` 存活
   （`GET /picture/get/vo` 返回 `code:0`，若 id 被截断成 `…324400` 这里必然 40400）。
4. 清理：软删 + `POST /picture/trash/purge`，逐条回包 `{pictureId:"2111027283732324354", ok:true, message:"已彻底删除"}`
   ——正好是 typings 里声明的 `PictureTrashResultVO[]`；再查该 id 得 `40400`。**库与 COS 零残留。**
5. **一处环境限制**：行内「恢复」用 Playwright 的 `click()` 会卡在可操作性等待（本机装了 vue-devtools 浮层），
   改用 DOM 级 `el.click()` 触发同一个 `@click` 处理函数——**真实鼠标点击路径没有证据**（与 `F17-F18-人工验收.md` 记的"彻底删除按钮点击未生效"同类）。

## 已知限制 / 未覆盖

1. **「刷新/翻页/勾选批量恢复」未在真机走**：本次只点了行内「恢复」。
2. **前端断言不进 CI 与 pre-commit**（与既有三个 check 脚本同待遇）：`check:format-size` 需手动跑；
   要自动跑得另改 `.github/workflows/ci.yml`（T30 记录里已记同一限制）。
3. **断言机制的坑（留档，下次抄）**：被断言的文件若 import 了 npm 包（本次是 `dayjs` / `file-saver`），
   转译产物**不能**放 `os.tmpdir()`（裸包名解析不到，实测 `ERR_MODULE_NOT_FOUND: file-saver`），
   且必须转成 **CJS**（`file-saver` 的 UMD 在 node ESM 下取不到命名导出，实测 `Named export 'saveAs' not found`）——
   脚本里已按此写并注释；无 import 的纯函数（`batchCrawlResult.ts` 那种）仍可按 T30 的老写法放临时目录。
4. **删除时间仍用页面本地的 `toLocaleString()`**，未换 `@/utils` 的 `formatDateTime`（输出格式不同、属用户可见变化，不在本任务范围）——
   同一页里"大小"走统一实现、"时间"仍是本地实现（观察，未改）。
