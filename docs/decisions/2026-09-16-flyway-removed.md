# 引擎移除 Flyway（2026-09-16）

> 对应 `docs/decisions.md` 的 2026-09-16 一条。理由与证据留在这里，口径以本表为准。
> 用户指示原话：「这个项目暂时不用 flyway」→「我不使用 Flyway，你可以把它相关的删除了」。

## 决定

引擎（`ai/agent`）**不再使用 Flyway**，相关配置与脚本一并移除，库结构改由**手工建表脚本**建立。

| 项 | 处理 |
|---|---|
| `ai/agent/src/main/resources/db/migration/V1__init.sql` … `V4__*.sql` | **删除**（`git rm`，整目录） |
| `ai/agent/pom.xml` 的 `org.flywaydb:flyway-mysql` | **删除依赖** |
| `ai/agent/src/main/resources/application.yaml` 的 `spring.flyway` 块 | **删除**（不再留 `enabled: false` 的空壳配置） |
| 库结构从哪来 | **新增** `deploy/sql/engine-schema.sql`（生产口径手工建表，见下） |

**生产建库路径随之变更**（`deploy/prod-checklist.md` 第 3、5 节已同步）：

```sql
CREATE DATABASE `yu-ai-agent` CHARACTER SET utf8mb4;
mysql -u root -p --default-character-set=utf8mb4 yu-ai-agent < deploy/sql/engine-schema.sql
```

`deploy/sql/engine-schema.sql` 的内容取自本机长期在跑的 `yu-ai-agent` 库（`show create table` 导出，只内联索引并加 `if not exists`），只建引擎代码真正会碰的四张表：`user` / `conversation` / `chat_message` / `chat_summary`。V2–V4 那批 interview / study / knowledge / agent_run 表**引擎代码一行都没引用**（`git grep` 全仓核实），不在脚本里。

## 为什么

**直接原因：用户不使用 Flyway。** 技术层面的两条事实与之同向，供后来人理解"为什么这条不该翻案"：

1. **`db/migration` 里的脚本在 MySQL 8 上跑不通。** `V1__init.sql` 的 `create index if not exists idx_conversation_id on chat_message (...)` 是 **MariaDB 语法**，MySQL 8 直接报语法错——实测复现（见下）。即"空库首跑 Flyway"这条路**本来就是坏的**，只是此前没人从空库跑过：本机那个库的 `flyway_schema_history` 里 V1 记的是 `<< Flyway Baseline >>`（`checksum` 为 NULL），说明**V1 从未被执行过**，真正跑过的只有 V2–V4。
2. **V2–V4 建的表是历史遗留，引擎用不到。** 那批表随引擎从源仓 `yu-ai-agent` 迁入，服务于已经停用的面试/学习类功能；图库助手只依赖上面那四张表。

## 证据（可复跑）

### V1 在 MySQL 8 上必然失败

```
$ mysql -uroot -p --default-character-set=utf8mb4 --database=<空库> < ai/agent/src/main/resources/db/migration/V1__init.sql
ERROR 1064 (42000) at line 42: You have an error in your SQL syntax; check the manual that
corresponds to your MySQL server version for the right syntax to use near
'if not exists idx_conversation_id
    on chat_message (conversation_id)' at line 1
```

失败前已建出 `user` / `conversation` / `chat_message` 三张表、**没有** `chat_summary`——即便忽略这处语法错，V1 本身也建不全引擎需要的表（`chat_summary` 当时根本不在 V1 里）。

另注：`create index if not exists` 这个写法**MySQL 8 全版本都不支持**（`IF NOT EXISTS` 只允许跟在 `create table` / `drop table` / `alter table ... add column` 后面）。

### 建表脚本 `deploy/sql/engine-schema.sql` 实测

在一个临时空库上执行，再与在跑的 `yu-ai-agent` 库逐列逐索引比对：

- **四张表全部建出**：`user` / `conversation` / `chat_message` / `chat_summary`；
- **逐列一致**（`column_type` / `is_nullable` / `column_default` / `extra` 四项全 same，26 列无 DIFF、无缺漏）；
- **逐索引一致**（`uk_username` / `fk_conversation_user` / `idx_conversation_id` / `uk_chat_id` + 各表 PRIMARY）；
- **可重复执行**：连跑两遍不报错，表数不变。

**一个踩到的坑（已固化进脚本）**：中文 Windows 上 mysql 客户端默认字符集不是 utf8mb4，直接跑会在 `title varchar(200) default '新对话'` 处报 `ERROR 1067 Invalid default value for 'title'`。脚本首行加了 `set names utf8mb4;`，从此不依赖客户端参数；文档里的命令仍建议带 `--default-character-set=utf8mb4`。这与 AGENTS.md 规则 10 是同一条教训（中文 Windows 默认 GBK）。

### 移除后引擎照常启动

- **门禁全量**（`.githooks/pre-commit` 同口径）：`ai/agent` **177 例绿**，删除前后各跑一次，例数与删除前一致；
- **全上下文装配**：`mvn -B test -Dtest=MyAiAgentApplicationTests` → `Started MyAiAgentApplicationTests in 5.753 seconds`，1 例绿（该用例需本机 MySQL/Redis，不属门禁口径）——证明摘掉 `flyway-mysql` 依赖后 Spring 上下文不受影响；
- 真机侧另有旁证：本机 8124 上正在跑的那个引擎进程，启动参数里带着 `--spring.flyway.enabled=false`（Flyway 尚未移除时，下午排练会话为绕开它显式关掉的）。

## 未覆盖 / 残余

- **本机库 `yu-ai-agent` 里的 `flyway_schema_history` 表成了死数据**（不再被任何代码读写）。留着无害，删不删都行，**未动**（属用户数据）。
- **本机还留着 5 个排练库**（`yu_ai_agent_rehearsal_*` / `yu_ai_agent_schema_fix_check_*`，2026-09-16 下午某次未记录会话所建，非本次产物），**未动**。
- **`ai/agent/create_sql/init.sql` 保留未删**：它是源仓遗留、被 `ai/agent/docker-compose.yml` 引用（库名 `my-ai-agent` 且以 `drop database` 开头，**不是生产口径**）。它没被 Flyway 那套消费，不属本次范围（规则 1）；生产建库口径已由 `deploy/sql/engine-schema.sql` 承担。
- **线上未动**：本次全部为本地仓库改动，未部署。
