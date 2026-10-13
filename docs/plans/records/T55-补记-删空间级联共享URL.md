# T55 补记 — 删空间级联的共享 URL 引用计数

> 2026-10-13。T55 主记录见 `docs/plans/records/T55-回收站共享URL引用计数.md`。
> 主记录把修复范围收在回收站/替换/回滚四条路径；本补记记**同日用户批准的范围扩展**——把删空间级联这条路径也纳入同一口径。
> 按规则 7，主记录"写一次不再编辑"，故扩展另开本文件。

## 起因

主记录末尾已按规则 1 上报：`SpaceServiceImpl.cleanupRemovedPictures`（**删除空间级联**清 COS）用的是**同一类只算存活行**的
引用计数（`pictureService.lambdaQuery().eq(url).count()`，被全局逻辑删除自动补 `isDelete = 0`），其注释还自述
"与 `PictureServiceImpl#cleanupPictureFile` 同口径"——T55 主修复后两者已不同口径，该路径存在与主 bug 完全相同的潜在误清。
用户同日批准修复。

## 场景

1. 空间 X 里有一张图，URL 为 `.../a.jpg`；
2. 另有一条记录（别处空间/公共图库）指向**同一个** `.../a.jpg`，且躺在**回收站**（`isDelete=1` + `deleteTime`，可恢复）；
3. 删除整个空间 X → 级联逻辑删掉空间 X 里那张图（**不带 `deleteTime`**，永久删），随后 `cleanupRemovedPictures` 判断
   `a.jpg` 还有无引用 → 漏掉回收站那条 → 以为无引用 → 删 COS；
4. 恢复第 2 条 → 其 URL 指向已删对象 → 死链。

## 修复

- `IPictureService` 新增 `long countUrlReferences(String url)`（口径 = 存活行 + 回收站内可恢复行），
  `PictureServiceImpl` 实现为委托 `getBaseMapper().countUrlReferences(url)`——把主修复里私有的引用计数口径提升为服务级能力，
  供跨服务复用（`SpaceServiceImpl` 依赖的是 `IPictureService` 接口，`getBaseMapper()` 在该接口上返回的是 `BaseMapper<Picture>`、
  拿不到自定义方法，故经服务方法暴露而非直接调 mapper）。
- `SpaceServiceImpl.cleanupRemovedPictures` 由 `lambdaQuery().eq(url).count()` 改为 `pictureService.countUrlReferences(url)`，
  注释同步为"与 `cleanupPictureFile` 同口径（走 `countUrlReferences`）"。
- 不新增依赖/表/进程；不改生效决定。

## 红绿输出（可复跑）

```
cd backend
mvn -B test -Dtest="SpaceDeleteCascadeIntegrationTest" -DfailIfNoTests=false
```

- **红（先写测试、修复之前）**：`Tests run: 3, Failures: 1`——新增 `deleteSpaceShouldNotCleanupUrlStillReferencedByTrashedRecord`
  失败，`clearPictureFile` 被调用（`verify(never())` 落空）。
- **绿（修复之后）**：`Tests run: 3, Failures: 0`。
- **负向控制（规则 12）**：把 `SpaceServiceImpl` 一行改回 `lambdaQuery().eq(url).count()` →
  单例精确全红，栈顶即 `SpaceServiceImpl.cleanupRemovedPictures`；恢复即绿。
- **全量回归**：后端集成套件 **97/97**（较主修复 +1，即本例）、单测门禁 **169/169** 绿。

新增例：临时建一个私有空间 + 空间内一张图（URL 记为 `U`），另造一条同 `U` 的记录并用裸 SQL 置为回收站
（`isDelete=1, deleteTime=NOW()`）→ 删空间 → 断言 `clearPictureFile` **未**以 `U` 被调用、回收站记录仍在且 `deleteTime` 未清。

## 环境与清理

- 用例造的临时空间与"回收站"记录由裸 SQL 物理清（`trashedPictureIds` / `extraSpaceIds`），避免 `isDelete=1` 残留污染回收站列表；
  COS 走 mock，未触真实对象存储。证据为 `mvn` 文本输出，可复跑。
