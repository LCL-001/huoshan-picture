# 火山图库压测资产说明

> 目的：让压测数字**可复现、可追溯、口径清楚**。
> 背景：早期文档里出现过「QPS 1186→9044、82ms→10ms」，这些数字在仓库内**没有任何原始数据支撑**，
> 已废弃；本文件所列为有 `statistics.json` 可查的口径。

---

## 1. 资产清单

| 文件 | 说明 |
|---|---|
| `lincode-loadtest-v3.jmx` | 主测试计划（JMeter 5.6.3）：3 个线程组，覆盖匿名读 / 登录 / 登录态 |
| `lincode-ab.jmx` | A/B 单路径计划：**一次运行只压一条路径**（由 `-Jsampler_path` 指定），含预热线程组与 health / tag_category 空跑基线 |
| `scripts/run-ab-staircase.ps1` | A/B 阶梯运行器：每个（路径 × 并发档）起一个**独立 JMeter 进程**，产出 `reports/` 与 `statistics-ab/` |
| `scripts/compare-ab.py` | 读 `statistics-ab/`，出两张表：同并发下 DB vs Cache（含扣除基线的 `net` 列）、各路径的阶梯与拐点 |
| `statistics/*.json` | 11 次运行的 JMeter 统计原始文件（`statistics.json`），本文件的数字全部来自这里 |
| `statistics-ab/*.json` | A/B 各次运行的 `statistics.json` 副本（运行器写入）。与 `statistics/` 同属"可复现的最小证据集"，**入库**；尚未实跑过时该目录不存在 |
| `scripts/summarize_reports.py` | 汇总脚本，读取 `statistics/` 生成下方表格 |
| `scripts/*.ps1` | 压测期间的辅助脚本（接口探测、结果分析） |

**未纳入仓库**（体积原因，`backend/.gitignore` 的 `jmeter/report*/` 兜住）：
- `*.jtl` 原始样本文件，单个最大 39MB（`r01-ano-100.jtl`）
- JMeter 生成的 HTML 报告目录，每个约 2.9MB，其中大部分是报表模板
- A/B 运行器写出的 `reports/`（jtl + HTML）同理不入库，只有 `statistics-ab/` 的 json 副本入库

---

## 2. 被测对象与环境

| 项 | 值 |
|---|---|
| 目标地址 | `https://lincode.online:443`（线上单机部署） |
| 服务端 profile | `local,test` / `prod,test`——test 仅关闭验证码与登录限流，数据源不变 |
| 压测端 | 本机 JMeter 5.6.3，**经公网访问**（与服务端不在同一内网） |
| 被测机器配置 | **待补**——仓库未记录 CPU/内存/带宽，面试被问会答不上来 |
| 数据集 | 图片表约 219 行、用户 25 行（本地库口径），与线上不完全一致，**待补线上数据量** |

> 公网 RTT 是响应时间的主要组成部分，这一点在解读所有延迟数字时都必须先说明（见 §5.1）。

---

## 3. 场景设计

三类线程组 + 一次混合运行：

| 线程组 | 场景 | 接口 | 线程参数（`-J` 覆盖） |
|---|---|---|---|
| TG1 | 匿名读 | `/api/health`、`/api/picture/tag_category`（纯静态基线）、`/api/picture/list/page/vo`（**走 DB**）、`/api/picture/list/page/vo/cache`（**走三级缓存**）、`/api/space/list/page/vo`、`/api/user/get/vo` | `t1`（默认 20）、`ramp1`（20）、`dur`（300s） |
| TG2 | 登录 | `/api/user/login`（BCrypt 校验） | `t2`（5）、`ramp2`（10）、`dur`（300s） |
| TG3 | 登录态 | 先登录建立会话，再轮询 `/api/user/get/login`、`/api/notification/unread`、`/api/notification/list`、`/api/spaceUser/list/my`、`/api/picture/get/vo` | `t3`（5）、`ramp3`（10）、`dur`（300s） |
| （脚本未留存） | 混合 | 上述三类按比例混合 | 见 `report04-mix` |

**注意 TG1 的采样器关系**：TG1 里同时放了「走 DB」与「走缓存」两个采样器，打的是**同一个分页列表查询**——但两者是在**同一次运行内并发**跑的，所以那两个数（§5.1 的 240 / 205）不能当作干净的缓存收益口径：它们互抢 Tomcat 线程、HikariCP 连接池与 CPU，缓存路径的延迟里含着 DB 路径的排队成本。

要测「缓存相对 DB 省了多少」，用 `lincode-ab.jmx`（§6.2）：它在**分进程的独立运行**里各压一条路径，每次运行自带 health / tag_category 空跑基线，并由 `compare-ab.py` 减出 `net` 列。

---

## 4. 结果汇总

数据来源：`scripts/summarize_reports.py`

| 场景 | 样本数 | 错误率 | 吞吐(QPS) | 平均响应(ms) | p95(ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| report（首次基线运行） | 70,169 | 0.0% | 233.89 | 85.29 | 98.0 |
| report01-ano-20 | 71,932 | 0.0% | 239.78 | 83.19 | 107.0 |
| report01-ano-50 | 148,759 | 0.0% | 495.88 | 100.61 | 127.0 |
| report01-ano-100 | 222,743 | 0.0% | 742.68 | 134.4 | 240.0 |
| report02-login-5 | 4,427 | 0.0% | 14.75 | 334.2 | 361.0 |
| report02-login-10 | 3,410 | 0.0% | 11.35 | 867.36 | 1479.45 |
| report02-login-20 | 6,018 | 0.0% | 20.04 | 981.9 | 1296.0 |
| report03-auth-5 | 18,379 | 0.0% | 61.26 | 80.42 | 115.0 |
| report03-auth-20 | 77,034 | 0.0% | 256.8 | 76.58 | 84.0 |
| report03-auth-50 | 170,960 | 0.0% | 569.93 | 86.24 | 94.0 |
| report04-mix | 144,917 | 0.0% | 241.46 | 155.05 | 292.0 |

- **全部 11 次运行累计样本 938,748，错误率均为 0.0%**
- 其中 **10 次分场景运行合计 868,579** ——简历里「累计 87 万请求零错误」即指这 10 次（不含首次基线运行）

---

## 5. 关键数字口径（面试必须能说清）

### 5.1 p95 240ms → 205ms

来自 `report01-ano-100`（100 线程）同一次运行中的两个采样器：

| 采样器 | 样本数 | 平均(ms) | p95(ms) |
|---|---:|---:|---:|
| `/api/picture/list/page/vo`（走 DB） | 37,151 | 158.84 | **240.0** |
| `/api/picture/list/page/vo/cache`（走三级缓存） | 37,133 | 130.79 | **204.95** |

**准确表述**：同一个分页列表查询，在「走 DB」与「走三级缓存」两条路径下 p95 分别为 240.0ms 和 204.95ms，差 35ms。

**不能这么说**：❌「接口优化后 p95 从 240 降到 205」——这不是同一接口的优化前后，是两条路径的横向对比。

**还要补一句**：这两条路径是在**同一次运行内并发**压的，互抢 Tomcat 工作线程、HikariCP 连接池与 CPU，缓存路径那份延迟里含着 DB 路径的排队成本。所以 35ms 是**有偏的近似值**（偏保守：真实收益应 ≥ 35ms），适合当"缓存确实有效"的定性证据；要拿干净数字，用 §6.2 的 A/B 计划分进程重跑。拿本节数字时不要省掉这句限定。

**必须补充的背景**：同一次运行里还有两个**空跑基线**采样器：
- `/api/health`（只回状态）p95 = 166ms
- `/api/picture/tag_category`（纯静态返回）p95 = 169ms

也就是说**什么都不查也要 166ms**，这是公网 RTT + HTTPS 握手/传输的成本。所以：
- 35ms 是缓存路径相对 DB 路径省下的部分，不代表服务端总耗时只有 200ms；
- 这组数字适合讲「相对收益」，不适合讲「绝对性能」；
- 如果想讲服务端净耗时，必须减去基线 RTT，或改用内网压测。

### 5.2 「单机聚合吞吐天花板约 750 QPS」

来自 `report01-ano-100` 的聚合吞吐 **742.68 QPS**：100 线程、**6 个接口混合**、持续 300s。

- 这是**混合负载的聚合值**，不是单个缓存接口的极限
- 对比同族的 `report01-ano-50`（495.88 QPS）与 `report01-ano-20`（239.78 QPS），吞吐随线程数近似线性，说明 100 线程时仍未见明显拐点——「天花板」是**该压测规模下的观测值**，不是理论上限

### 5.3 「累计 87 万请求零错误」

10 次分场景运行的 `sampleCount` 之和 = 868,579，每次都 `errorPct = 0.0`。

**注意**：这不是一次压测跑出来的，是多次运行累加。表述时要带「累计」二字。

### 5.4 登录接口是最大瓶颈（现有资产里最有说服力的一条）

| 场景 | 平均(ms) | p95(ms) |
|---|---:|---:|
| report02-login-5 | 334.2 | 361.0 |
| report02-login-10 | 867.36 | 1479.45 |
| report02-login-20 | 981.9 | 1296.0 |

登录远慢于其他接口（其他接口普遍 < 100ms）。原因是 **BCrypt 校验是 CPU 密集型**，且强度参数越高越慢。

这是**有意的安全成本，不是缺陷**。被追问时可以讲优化方向：降低 BCrypt strength、改用 Argon2 并按机器调参、或对登录接口单独限流保护。

### 5.5 混合场景

`report04-mix`：144,917 样本、241.46 QPS、平均 155.05ms、p95 292ms。混合负载下 p95 明显高于单一场景，符合预期。

---

## 6. 复现方式

### 6.1 v3 全场景复现

```bash
# 1. 服务端先启用 test profile（关闭验证码与登录限流），否则登录接口会大量失败
#    java -jar xxx.jar --spring.profiles.active=prod,test

# 2. 运行压测（账号口令通过 -J 传入，不要写回 jmx）
jmeter -n -t lincode-loadtest-v3.jmx \
  -Jt1=100 -Jramp1=20 -Jdur=300 \
  -JTEST_ACCOUNT=<账号> -JTEST_PASSWORD=<口令> \
  -l result.jtl -e -o report-ano-100

# 3. 汇总
python scripts/summarize_reports.py statistics
```

可覆盖参数：`t1`/`ramp1`/`t2`/`ramp2`/`t3`/`ramp3`/`dur`/`TEST_ACCOUNT`/`TEST_PASSWORD`/`TEST_USER_ID`/`TEST_PICTURE_ID`

### 6.2 A/B 阶梯（测「缓存相对 DB 省了多少」）

不要用手敲 `jmeter` 命令，直接跑运行器；它会为每个（路径 × 并发档）各起一个**独立 JMeter 进程**，并把每次运行的 `statistics.json` 复制到 `statistics-ab/`。

```powershell
cd backend/jmeter/scripts

# 公网（默认目标 lincode.online:443，20/50/100 三档，每档 300s）
.\run-ab-staircase.ps1

# 本机实例：基线 RTT 大幅下降，DB 与缓存的差值才接近真实服务端差值
.\run-ab-staircase.ps1 -TargetHost 127.0.0.1 -Port 8123 -Protocol http -Levels 20,50,100 -Duration 120

# 只压缓存路径、只跑一档
.\run-ab-staircase.ps1 -Paths Cache -Levels 100
```

运行器跑完会自动调 `compare-ab.py`，输出两张表：同并发下 DB vs Cache（含扣除基线的 `net` 列），以及各路径的阶梯与拐点。也可手工重跑对比：

```bash
python scripts/compare-ab.py backend/jmeter/statistics-ab
```

**为什么必须分进程**（这条是本目录的口径，不要退回"一次运行里放两条路径"）：同一 JVM 内并发压两条路径时，慢的 DB 查询会占满 Tomcat 工作线程与 HikariCP 连接池，缓存路径的延迟里就含进了 DB 的排队成本，测出来是混合成本而不是路径成本。分进程还有两个附带好处：没有 JIT 与连接池的跨运行残留，也没有采样器顺序偏置。

**为什么每次运行都带基线**：目标通常在公网，响应时间的大头是 RTT + HTTPS，与服务器无关。`compare-ab.py` 的 `net` 列减掉的是**同一次运行内**测出的最慢基线——这是唯一站得住脚的比法。仍是近似：基线线程组跑在低并发，被测路径跑在 N 线程，两者的争用程度并不相同。

> **注意**：截至本次提交，这套 A/B 资产**尚未实跑过**（`statistics-ab/` 不存在），`compare-ab.py` 只做过空目录、语法与样本冒烟。首次实跑后请把真实输出贴回 `docs/plans/records/`，再引用具体数字。

### 6.3 边界：这些接口不要压

凡涉及**外部第三方计费**的接口一律不进常规压测范围，避免资金损失或账号被外部平台风控封禁：

| 不压的接口 | 原因 |
|---|---|
| `/api/picture/out_painting/create_task` | 阿里百炼 AI 扩图，按次计费 |
| `/api/agent/chat/sse`、`/ai/**` | 大模型 Token 消耗 |
| MCP 搜图（Pexels） | 付费或限流，且 `ai/image-search-mcp-server` 自身是独立进程 |
| 真实对象存储大文件上传 | 腾讯云 COS 流量与请求计费 |

压测资产聚焦在**本地单机核心链路**：数据库、Caffeine/Redis 缓存、Sa-Token 鉴权与容器吞吐。

---

## 7. 已知缺失与改进项

| 项 | 影响 | 建议 |
|---|---|---|
| 被测机器配置、线上数据量未记录 | 面试被问「什么配置、多少数据」答不上来 | 补进本文件 |
| 混合场景（`report04-mix`）的 jmx 未留存 | 只有统计结果，无法复现 | 按 §3 的三类比例重建 jmx |
| `report`（无后缀）是一次基线运行 | 命名不规范，容易被误当成模板目录 | 重命名为 `report00-baseline` |
| 原始 `.jtl` 未入库 | 无法从原始样本重新计算其他分位数（p99 等） | 如需保留，考虑压缩后放外部存储 |
| 未做内网压测 | 延迟被公网 RTT 主导，无法体现服务端真实性能 | 条件允许时补一次内网基线 |
| A/B 资产（`lincode-ab.jmx` + 运行器）尚未实跑 | §5.1 的 240/205 是同运行并发两路径，含排队成本；干净口径还没有数字 | 跑一次 `run-ab-staircase.ps1`，把输出贴进 `docs/plans/records/` |
| §3 的 TG1 同运行并发压两条路径 | 与 §6.2 的分进程口径冲突，那组数字只作定性证据 | 已在本文件注明偏保守；新数字一律走 A/B |

---

## 8. 安全提示

原 jmx 中内置了测试账号的**明文口令与账号**，而本仓库是公开仓库、`lincode.online` 是线上站点，等同于公开一份可用凭据。

本次已改为参数化（`${__P(TEST_ACCOUNT,)}` / `${__P(TEST_PASSWORD,)}`），运行时通过 `-J` 传入。

**同时建议**：如果该账号口令在历史提交或其他仓库中出现过，请直接改密。
