# 实测发现：provider 不可达族失败落在 error 事件之外（已收口）

> 自 docs/decisions.md 外移并压缩（2026-09-18 文档保养）；表格行**完整原文逐字保留**如下。

## 原始表格行（压缩前）

| 2026-09-15 | **实测发现（T8-d 真机，挂账不修）："provider 不可达"这一族失败落在 T8-c 的 error 事件之外**——把 assistant base-url 指向黑洞地址（`http://127.0.0.1:9`）后，Spring AI `RetryUtils` 按 R2 记录的策略重试 `ResourceAccessException`（引擎日志实测 retry 1→4，间隔 2s/10s/50s…指数退避、上限 180s），10 次重试的总时长**远超引擎 `SseEmitter` 的 300s**，于是客户端先被 300s 超时掐断（`AsyncRequestTimeoutException` + "SSE connection timed out."），error 事件没有机会发出。 | 影响：这一族失败下用户看到的仍是"连接中断"而非 error 文案，且那 300s 内引擎一帧不发。**要收口需一次决策**（给重试预算封顶 / 关掉该路径重试 / 把 emitter 超时与重试预算对齐 / 接受并写进文档），与 R1（取消不关闭上游）同属"重试与连接生命周期"一族，超出 T8 范围，本轮只记录不修。证据：`%TEMP%\t8\engine-after-err.log`（10:28:36 超时）与 `%TEMP%\t8\summary.txt` 第三节。 | 挂账 |
