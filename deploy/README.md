# deploy/ —— 部署清单与 nginx 片段（**入库**）

## 为什么在这里，而不是 `backend/docs/deploy/`

`backend/docs/deploy/` 才是历史习惯的位置，但它被 `backend/.gitignore` 的 `docs/` 规则排除、**整个目录不在版本控制内**（`git ls-files backend/docs` 为空）。2026-09-15 复核时认定这是个真问题：

- AGENTS.md 与多条任务都指向那里，但**新克隆的仓库里没有那些文件**；
- 更关键的是"上线要用的 nginx 配置"没有落点——磁盘上那份 `nginx-security.conf` 只是早先安全批次的响应头片段，**不含**助手 SSE 所需的 `proxy_buffering off` / `proxy_read_timeout`。

所以一期的部署产物改放**入库**的 `deploy/`；`backend/docs/deploy/` 保持原样（本机历史文件，不动它，也不指望它）。

## 里面有什么

| 文件 | 用途 |
|---|---|
| `prod-checklist.md` | **D4**：引擎 / MCP / 后端三个进程的 prod 配置清单、启动顺序、上线自检表，以及两个会直接坑住人的陷阱 |
| `nginx/ai-assistant-sse.conf` | **D3**：合并进 `lincode.online:443` 的 SSE location 片段（关缓冲、放宽读超时） |
| `sql/engine-schema.sql` | **引擎建表脚本（生产口径）**：2026-09-16 引擎移除 Flyway 后，首次部署必须手工执行它建四张表（`user` / `conversation` / `chat_message` / `chat_summary`）；幂等，可重复执行。口径见 `docs/decisions/2026-09-16-flyway-removed.md` |

## 阅读顺序

先看 `prod-checklist.md` 第 0 节的**两个陷阱**（jar 里烤了本机配置 + 后端默认 profile 是 `local`；引擎的 `thinking: disabled` 不在入库配置里）——这两条不看清，后面所有步骤都白做。

## 状态

**两者都未在真机验证过**：写它们的人没有服务器访问权，内容是照代码与配置推导的。合并 nginx 前请自行 `nginx -t`；上线自检表里每一步都写了期望输出。
