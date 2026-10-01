# 证据 — 2026-10-01 写确认门上线只读核验（可复跑）

> 背景：用户报告写确认门（T27.4–T27.9 + T27.2 + T41/T42 文案同车）已于 2026-10-01 部署上线（三件套 backend → 引擎 → 前端）。本页记录**无凭据只读核验**的命令与输出。
> 复跑方式：以下 curl 均为幂等只读探测——不带登录态，必然在鉴权层被拒，对线上零写入、零状态变更。

## 1. backend 确认/准备端点（新路由存在性）

```
curl -s -w "\n[HTTP %http_code]\n" -X POST -H "Content-Type: application/json" -d "{}" "https://www.lincode.online/api/ai/assistant/operations/confirm"
→ {"code":40100,"data":null,"message":"未登录"}

curl -s -w "\n[HTTP %http_code]\n" -X POST -H "Content-Type: application/json" -d "{}" "https://www.lincode.online/api/ai/assistant/operations/prepare"
→ {"code":40100,"data":null,"message":"未登录"}
```

判据：40100（未登录）= Sa-Token 登录门槛先于业务逻辑，两条路由存在 ⇒ 线上 backend 为新 jar（T27.5 原子确认端点 + T27.6 准备端点已部署）；旧 jar 此处为 404。

## 2. 前端指纹（与当前 main 构建产物逐哈希一致）

- 线上 `https://www.lincode.online/` 的 index.html 引用：`/assets/index-UAuOZVhi.js`、`/assets/index-Bkppf_ma.css`
- 本地 2026-10-01 基于 main 执行 `cd frontend && npm run pure-build`（vite 6.0.3，4020 modules，23.7s）产物：`dist/assets/index-UAuOZVhi.js`、`dist/assets/index-Bkppf_ma.css`

两者完全一致（Vite 内容哈希）⇒ 线上前端 = 当前 main，含 T27.7 确认卡片、T27.9 超时修复与 T41/T42 两轮文案。

## 3. 引擎（8124，仅回环）——未核验

引擎按 D1 只监听 127.0.0.1，公网无可达探针。间接验证需登录态：发一个批量导入请求应出现确认卡片（只有新引擎 T27.6 的 prepare 写工具会触发卡片）；若引擎 jar 陈旧，历史回看会复现 09-29 那次的 404"引擎暂时不可用"形态（见归档 T36–T39 条目原注）。

## 尚未核验（需登录态，待用户自检）

- 卡片出现 / 确认中 / 取消 / 刷新四条路径
- 测试空间一次真确认（真写入 + 扣额度）
- 多图导入（>10s）的线上表现（T27.9 验收点：不再有 10s 谎报失败）
