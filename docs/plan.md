# Plan — 火山图库（huoshan-picture）

> 任务粒度：一个任务 = 一次可测试的改动 = 一个 commit。每个任务必须标注允许触碰的文件范围。

## 任务清单

以下为从 git 记录推断的近期方向（**推断，待用户确认后正式生效**）：

- [x] T1 工程化收尾：端口统一 8123、README 启动步骤修正、Redis 键前缀统一 `huoshantuku:` 并迁移存量数据（5c0ed24 / 5faf41f / fe71e78 / 596cb8f）
  - 文件范围：backend 配置与缓存相关类、README
  - 验收：已提交，CI 绿
- [x] T2 CI 门禁：GitHub Actions 跑编译 + 无 MySQL/Redis 单测（4f4cd5b）
  - 文件范围：.github/workflows/ci.yml
  - 验收：CI 绿
- [ ] T3 下一步方向未知——待用户在此登记（一任务一 commit，附文件范围与验收标准）
