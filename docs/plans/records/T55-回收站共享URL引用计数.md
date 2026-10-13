# T55 — 回收站共享 URL 引用计数

> 2026-10-13。来自两日改动 review 修复批次（plan.md T55，P1）；功能讲解同步在 `docs/features/F17-回收站.md`。本文件只记实施过程、红绿输出与证据。

## 范围

- 代码：`backend/service/impl/PictureServiceImpl.java`（`cleanupPictureFile`）、`mapper/PictureMapper.java`、`resources/mapper/PictureMapper.xml`（新增 `countUrlReferences` 裸 SQL）。
- 测试：`PictureTrashIntegrationTest`（彻底删）、`PictureTrashRetentionIntegrationTest`（过期清理）、`PictureReplaceQuotaIntegrationTest`（替换）。
- 不新增依赖、表或进程；不改生效决定（2026-10-12「图片回收站口径」不变）。

## 根因

`cleanupPictureFile` 判"该 URL 还能否清 COS"时用的是 MyBatis-Plus 的 `lambdaQuery().eq(url).count()`。
全局逻辑删除（`@TableLogic` + `mybatis-plus.global-config`）会给该查询**自动补 `isDelete = 0`**，
于是**仍在回收站、可恢复**的行（`isDelete = 1` 且 `deleteTime` 不为空）被漏掉。

后果：同一 URL 有两条记录、两条都进了回收站时——彻底删（或保留期清理）第一条 → 引用计数读到 0 →
`clearPictureFile` 把 COS 文件删掉；第二条之后被**恢复**出来，它的 `url` 就指向一个已被删除的对象，成了死链。
`cleanupPictureFile` 是彻底删除、过期清理、图片替换、事务回滚补偿四条路径的唯一出口，四者同源，故一并受此影响。

## 修复

- `PictureMapper#countUrlReferences(url)` + `PictureMapper.xml` 裸 SQL：引用 = **存活行 + 回收站内可恢复行**
  （`isDelete = 0 OR (isDelete = 1 AND deleteTime IS NOT NULL)`）；空间/用户级联的永久删除（`isDelete = 1`
  且 `deleteTime` 为空）不可恢复，不计入引用。
- `cleanupPictureFile` 改调 `getBaseMapper().countUrlReferences(url)`，四条调用路径统一收敛到这一处口径。

## 红绿输出（可复跑）

命令（本机需 MySQL 3306 + Redis 6379，`application-local.yaml` 就位）：

```
cd backend
mvn -B test -Dtest="PictureTrashIntegrationTest,PictureTrashRetentionIntegrationTest,PictureReplaceQuotaIntegrationTest" -DfailIfNoTests=false
```

- **红（先写测试、修复之前）**：`Tests run: 16, Failures: 3, Errors: 0`
  - `PictureTrashIntegrationTest.sharedUrlPurgeKeepsCosUntilLastReferenceGone`（:185）
  - `PictureTrashRetentionIntegrationTest.expiredPurgeKeepsCosWhenAnotherTrashedReferenceRemains`（:140）
  - `PictureReplaceQuotaIntegrationTest.replaceShouldNotCleanupUrlStillReferencedByTrashedRecord`（:248）
  - 三例失败原因一致：`pictureFileCleanupService bean.clearPictureFile(...)` 被调用了——`verify(never())` 落空，即"仍在回收站的同 URL 记录"没能拦住清理。
- **绿（修复之后）**：同一命令 `Tests run: 16, Failures: 0, Errors: 0`。
- **负向控制（规则 12）**：把 `PictureMapper.xml` 的谓词缩回旧语义 `AND (isDelete = 0)`（只算存活行），
  上面三例精确全红（`Tests run: 3, Failures: 3`）；恢复正确谓词后回到绿。证明"计入可恢复行"这条判据是承重的，
  而不是测试恰好通过。
- **全量回归**：后端集成套件 `mvn -B test -Dtest="*IntegrationTest,RedisStringTest,YunPictureBaseApplicationTests"` → **96/96 绿**；
  单测门禁口径 `mvn -B test -Dtest="!*IntegrationTest,!RedisStringTest,!YunPictureBaseApplicationTests"` → **169/169 绿**。

新增三例覆盖（验收口径：同 URL 两张均入站 → 删首张不清 COS 且另一张可恢复 → 最后一条引用删除才清；覆盖过期清理）：

- 彻底删：两张同 URL 进回收站 → 彻底删第一张（断言此时**未**清 COS）→ 恢复第二张成功 → 再删并彻底删第二张（断言此时才清 COS）。
- 过期清理：`expiredPicture` 回拨到保留期外，另一张同 URL 记录刚进站 → 跑一轮 `purgeExpiredTrash` → 过期行物理消失、
  同 URL 记录仍在回收站、**未**清 COS。
- 替换：`first` 被替换（旧 URL 失去一条存活引用），旧 URL 的另一条记录已在回收站 → 断言**未**清 COS。

回滚补偿路径（T3.11）无独立新例：补偿对象是刚上传、从未落库的记录，其 COS 路径唯一，不存在同 URL 的回收站引用，
行为不变；既有 `PictureReplaceQuotaIntegrationTest` 的两例 T3.11 用例继续绿。

## 发现（未在本次范围内修，按规则 1 上报）

`SpaceServiceImpl.cleanupRemovedPictures`（删空间级联清理 COS）用的是**同一类**只算存活行的引用计数，
注释亦自述"与 `PictureServiceImpl#cleanupPictureFile` 同口径"——本次修改后两者已**不再同口径**。
该文件不在 T55 文件范围内，未改；它存在与本次相同的潜在误清（级联删空间时，同 URL 的回收站记录会被漏计）。
建议后续单独立项或并入 T56 一起处理。

## 环境与清理

- 三个测试类自建/自清夹具：`PictureTrashIntegrationTest`、`PictureTrashRetentionIntegrationTest` 用裸 SQL 物理删残留行；
  `PictureReplaceQuotaIntegrationTest` 新增的"回收站 sharer"行用裸 SQL 置位、收尾用裸 SQL 物理删
  （逻辑删拦不住 `isDelete=1` 的行）。COS 一律走 mock，未触真实对象存储。
- 证据为 `mvn` 文本输出，可复跑；无截图/大日志类不可复跑证据。
