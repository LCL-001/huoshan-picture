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
- [ ] T3.4 重复上传额度重复累计修复（更新分支改为 `totalSize - oldSize + newSize`、count 不变）+ 旧 COS 对象清理
  - 文件范围：service/impl/PictureServiceImpl.java（uploadPicture 事务段）
  - 验收：集成测试断言替换图片后空间额度净增量 = 新旧差值
- [ ] T3.5 删空间级联（事务内逻辑删图片 + 异步清 COS + 包事务）、删用户级联
  - 文件范围：service/impl/SpaceServiceImpl.java、controller/UserController.java
  - 验收：集成测试断言删空间后图片与文件被清理
- [ ] T3.6 ShardingSphere 二选一：修复启用（库名不一致/空 range 静默丢数据/动态建表回退主表）或删除死代码死配置并改 README
  - 文件范围：YunPictureBaseApplication.java、manager/sharding/**、application.yaml、README.md
  - 验收：启用则集成测试过；删除则仓库无 shardingsphere 残留
- [ ] T3.7 社交通知口径二选一：SocialController 一并停用，或修正 spec/AGENTS 记录为"通知功能保留"
  - 文件范围：controller/SocialController.java 或 docs/spec.md
