# Plan — 火山图库（huoshan-picture）

> 任务粒度：一个任务 = 一次可测试的改动 = 一个 commit。每个任务必须标注允许触碰的文件范围。

## 任务清单

以下为 2026-09-12 功能审查（三个只读子代理 + 逐条人工核验，详见 handoff/0002）产出的修复批次，按优先级排列：

- [x] T1 工程化收尾：端口统一 8123、README 启动步骤修正、Redis 键前缀统一 `huoshantuku:` 并迁移存量数据（5c0ed24 / 5faf41f / fe71e78 / 596cb8f）
  - 文件范围：backend 配置与缓存相关类、README
  - 验收：已提交，CI 绿
- [x] T2 CI 门禁：GitHub Actions 跑编译 + 无 MySQL/Redis 单测（4f4cd5b）
  - 文件范围：.github/workflows/ci.yml
  - 验收：CI 绿
- [x] T3.1 图片读接口权限绑定目标资源，封堵"请求嗅探"越权
  - 文件范围：manager/auth/StpInterfaceImpl.java、config/HttpRequestWrapperFilter.java、controller/PictureController.java、service/impl/PictureServiceImpl.java、backend/src/test/（新增 HttpRequestWrapperFilterTest、PictureSpaceViewAuthIntegrationTest）
  - 验收：单测进门禁全绿；集成测试钉住"charset 变体令上下文为空 / spaceUserId 走私劫持 / 详情接口越权读"三条攻击路径全部 40101，属主与 viewer 成员的合法路径不受影响
- [x] T3.2 `/space/list/page/vo` 匿名枚举收紧（仅返回本人空间与已加入团队空间，admin 除外）；`/space/get/vo` 同步收紧为"与空间有归属关系才可查"
  - 文件范围：controller/SpaceController.java、service/impl/SpaceServiceImpl.java、service/ISpaceService.java
  - 验收：SpaceListAuthIntegrationTest 9 用例全绿（匿名 40100、无关用户不可见他人空间、viewer 可见已加入团队空间、属主/管理员正常）；已提交 c2f61e6
- [x] T3.3 会话生命周期：改密/删号/降权踢 Sa-Token 会话；getLoginUser 匿名请求不建会话
  - 文件范围：service/impl/UserServiceImpl.java、controller/UserController.java
  - 验收：UserSessionLifecycleIntegrationTest 4 用例全绿（resetPassword 后旧 satoken 失效且新密码可登录、删号踢会话、降权踢会话、匿名请求零会话）；已提交 5501642。已知残余：Spring Session（非 Sa-Token）在改密后仍有效，但因空间接口全部依赖 Sa-Token 登录态，残余影响限于个人资料编辑等自持操作，量级低
- [x] T3.4 重复上传额度重复累计修复（更新分支改为 `totalSize - oldSize + newSize`、count 不变）+ 旧 COS 对象清理
  - 文件范围：service/impl/PictureServiceImpl.java（uploadPicture 事务段）+ backend/src/test/（新增 PictureReplaceQuotaIntegrationTest）
  - 验收：PictureReplaceQuotaIntegrationTest 5 用例全绿（替换后额度净增量 = 新旧差值、缩图替换回落、超限替换整笔回滚且不清理旧文件、旧 URL 仍被其它记录引用时跳过清理、个人图库无空间也清理旧文件）；已提交 ad4dada
  - 实现备注：扣减用 `GREATEST(totalSize - oldSize, 0) + newSize`（与 deletePicture 同款防负数）；`cleanupPictureFile` 引用计数阈值由 `count > 1` 改为 `count > 0`（调用点查询时记录本身已删/已改指向，剩 1 条引用也不能删共享文件，原阈值会在恰好剩一条引用时误删）
- [x] T3.5 删空间级联（事务内逻辑删图片 + 异步清 COS + 包事务）、删用户级联
  - 文件范围：service/impl/SpaceServiceImpl.java、controller/UserController.java、service/ISpaceService.java（新增 deleteUserCascade 声明，接口变更超出原定范围已在此说明）+ backend/src/test/（新增 SpaceDeleteCascadeIntegrationTest）
  - 验收：SpaceDeleteCascadeIntegrationTest 2 用例全绿（删空间后图片逻辑删除、URL 仍被他人空间引用的图跳过清理、成员记录清理、他人空间不受影响；删号后名下空间级联、他人空间成员关系移除、其上传到他人空间的图保留）；全量 54 测试绿；已提交 8c702c1
  - 口径备注：删空间为单事务（空间行 + 成员 + 图片），COS 清理在事务提交后按 URL 引用计数异步执行；图片列表缓存不在级联中失效（空间删除后其缓存条目已不可达，TTL 兜底）；删号保留其上传到他人团队空间的图片（团队内容不随账号消失），如需一并删除另行立任务
- [x] T3.6 ShardingSphere 二选一 → 用户拍板第三选项：**注释停用（不删不启）**
  - 实际改动：application.yaml 分表死配置块注释停用（application-local/prod.yaml 属 gitignore 本地同步同步注释）；主类 exclude、pom 依赖、manager/sharding 两类保持原状作为停用护栏与恢复基础；README 本无分表表述，不改
  - 验收：全量 54 测试绿（上下文启动正常）；口径记入 decisions.md；已提交 d000360
- [x] T3.7 社交通知口径二选一 → 用户拍板：**保留通知接口，修正 spec/AGENTS 记录**
  - 实际改动：仅文档（docs/spec.md F7、AGENTS.md 技术栈与停用模块注记、decisions.md 两条口径）；前端核实 GlobalHeader.vue 正在调用 /notification/* 五接口，SocialController 未改动
  - 验收：spec F7 改为"部分停用：通知保留"；decisions.md 记入 T3.6/T3.7 两条口径
- [x] T3.8 空间额度预检误拒替换图片（T3.4 顺手发现的存量问题，用户 2026-09-13 点名）：预检移到 oldPicture 解析之后，仅对新增图片按新增口径预检；替换不增条数、大小按净差值在事务内原子校验，满员空间替换（缩图/等量）不再被"空间条数不足/大小不足"误拒
  - 验收：PictureReplacePrecheckOwnershipIntegrationTest——满员空间替换成功且额度记净差值（1000-500+200=700、条数不变）；满员空间新增仍被拒（守卫用例）；已提交 4d37b45
- [x] T3.9 admin 替换他人图片归属被改（T3.5 顺手发现的存量问题，用户 2026-09-13 点名）：getPicture 更新分支保留原归属 userId，仅新增图片归属上传人；管理员替换不再把图片改成自己的
  - 验收：admin 替换后 userId 不变、URL 更新；PictureReplacePrecheckOwnershipIntegrationTest 3 用例全绿；全量 57 测试绿；已提交 4d37b45
  - 独立 review（规则 13，2026-09-13）：**通过**（8 用例实测绿，静态推演确认回退即红）。P2 既有问题（非本次引入）已立 T3.10 收口。P3 已知限制：满员空间超额替换在 COS 上传后于事务内被拒、留孤儿对象（与新增路径既有失败面一致）；回归测试属 IntegrationTest 口径不入 CI/门禁（既有约定）
- [x] T3.10 替换图片 spaceId 反推路径缺空间上传权限校验（T3.8/T3.9 独立 review 发现的既有 P2，用户 2026-09-13 拍板收口）：uploadPicture 在 spaceId 由原图反推时补查目标空间并校验 PICTURE_UPLOAD（抽 checkSpaceUploadPermission 私有方法复用），与显式传 spaceId 路径、checkPictureAuth（deletePicture）判权同源
  - 口径后果（有意收紧）：站点管理员若非团队空间成员，替换/上传该空间图片将被拒（与显式传参路径及 deletePicture 现状一致）；私有空间属主/站点管理员不受影响，团队空间属主由 createSpace 自动建 admin 成员行、不受影响
  - 验收：PictureReplaceSpaceAuthIntegrationTest 4 用例全绿（被移出成员替换被拒、降权 viewer 替换被拒、非成员站点管理员替换被拒、editor 成员正常替换且归属不变）；全量 61 测试绿；已提交 8b29f46
- [x] T3.11 上传事务失败后补偿删除刚上传的 COS 文件（P3 孤儿对象收口，用户 2026-09-13 点名选项①）：transactionTemplate.execute 包 try-catch，事务回滚后按引用计数（count == 0 才删）异步清理刚上传的新文件；原异常照常抛出。覆盖替换超额与"过预检但事务内原子校验失败"的新增两条失败路径
  - 口径：COS 上传在事务前这一时序不变（picSize 只有上传后才知道）；cleanupPictureFile javadoc 补"事务回滚后清理孤儿文件"第三类调用前提
  - 验收：PictureReplaceQuotaIntegrationTest 6 用例全绿（超额替换：旧文件绝不动 + 新文件被补偿清理；新增超额：补偿清理；另 4 用例回归不变）；全量 62 测试绿；已提交 5f44ecc
