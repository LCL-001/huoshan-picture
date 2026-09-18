# 凭据组口径（B 落地）

> 自 docs/decisions.md 外移并压缩（2026-09-18 文档保养）；表格行**完整原文逐字保留**如下。

## 原始表格行（压缩前）

| 2026-09-14 | **凭据组口径（B 落地）**：一期设计的"代理透传调用者 satoken"扩展为**代理透传一组凭据（satoken + Spring Session 会话标识）**——引擎 headless 端点从 `SESSION` cookie 取会话标识（与 backend 的 Spring Session 默认 cookie 名一致），`HuoshanApiClient` 调图库时同时带 `satoken` 头与 `Cookie: SESSION=…`；两把凭据都**只在请求生命周期内存流转**，不落库、不进日志、不作为对话记忆内容，缺任一把在端点入口即拒（`40000`，不再拖到工具调用阶段才炸 40100/40102）。引擎不自行校验两把是否属于同一用户，交由图库判定（不一致时回 `SPACE_NOT_LOGIN`）| B 的必然结果；同时修正 2026-09-13「空间接口全部依赖 Sa-Token 登录态」的不实前提（空间列表实际依赖 Spring Session）。实测：真登录探针账号下，`listSpaces` 读到真实空间、空间维度 `listPictures` 回 `code=0`（通过加强校验），仅带 satoken 时回 40000 | 生效 |
