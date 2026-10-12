# T48 实施记录 — 集成测试入 CI

> 依据：`docs/plan.md` T48；口径 `docs/decisions.md` 2026-10-12「CI 跑集成测试」（**取代** 2026-09-13「集成测试不入 CI」行）。
> 字据提交 `f33ae25`，实现提交 `898c22e`。规则 12「门禁指向快速子集、全量回归交给 CI」由此落地。

## 改了什么

| 文件 | 改动 |
|---|---|
| `.github/workflows/ci.yml` | 加 `mysql:8.0` + `redis:5` service 容器（带健康检查）；加 "Init MySQL schema" 步骤（apt 装 mysql-client，按序灌三个建表脚本）；加 "Integration tests" 步骤跑 `*IntegrationTest,RedisStringTest,YunPictureBaseApplicationTests`（`-Dspring.profiles.active=local,ci`、env `DB_PASSWORD=root`）。原"纯单测"快速步骤保留不动 |
| `backend/src/test/resources/application-ci.yaml`（新） | CI 没有 `application-local.yaml`，补上"没有它上下文起不来"的两项：`aliYunAi.apiKey`（`AliYunAiApi` 的 `@Value` **无默认值**）、`cos.client.*`（`CosClientConfig` 构造 COSClient 需要非空 `region`，SDK 会校验）。本地默认 profile 不含 `ci`，对本机零影响 |
| `backend/src/test/java/.../AiAssistantProxyIntegrationTest.java` | 修一处过期断言（见下）。**测试补强，规则 3 免字据** |

## 顺带发现并修掉：一处过期断言（"集成测试不进 CI"的直接代价）

`AiAssistantProxyIntegrationTest.rejectsWhenBrowserRequestCarriesOnlySatokenWithoutSessionCookie` 断言旧文案「缺少会话 Cookie」，而生产代码自 T42 去术语起（提交 `6b60437`，提交信息自称"断言同改"）抛的是「登录信息不完整，请重新登录」。当轮只改了**门禁里跑得到的** `AiAssistantControllerTest`，**集成测试那份漏改**——它不进 CI，所以一直没被跑出来。T48 把它跑起来，第一次就红。修法：断言改为「登录信息不完整」，与单测同口径；生产文案不动（T42 决定）。

## 证据（可复跑）

环境：本机 MySQL 8.0.43（3306）、Redis（6379）、JDK 17.0.18。命令均在 `backend/`，`JAVA_HOME=D:\develop\jdk17`。

1. **本机全量集成测试（默认 profile，真库 + 真 Redis）**
   `mvn -B test -Dtest="*IntegrationTest,RedisStringTest,YunPictureBaseApplicationTests" -DfailIfNoTests=false`
   → 修复前 **86 例 / 1 失败**（即上面那处过期断言）；修复后 **86/86 绿**（`BUILD SUCCESS`）。
2. **CI 模拟（`-Dspring.profiles.active=local,ci`，即吃 `application-ci.yaml` 的占位 COS/阿里云）**
   同上命令加 `-Dspring.profiles.active=local,ci` → **BUILD SUCCESS，86/86 绿**，日志确认 `The following 2 profiles are active: "local", "ci"`。证明占位配置足以让上下文起来、不依赖真实 COS/视觉密钥。
3. **本地缺失配置的静态比对**：`application-local.yaml` 的全部键 = `cos.client.{host,secretId,secretKey,region,bucket}` + `aliYunAi.apiKey` + `datasource.password` + `mail.{username,password}` + `app.ai.vision.{base-url,api-key,model}`；后三类 `application.yaml` 已用 `${ENV:}` 兜底（CI 用 `DB_PASSWORD` 提供），**只有前两项是 CI 缺的**，已进 `application-ci.yaml`。
4. **全新库建表脚本探针**（验证 CI 的 Init MySQL schema 步骤）：在临时库 `yp_ci_probe` 上按序跑 `create_table.sql` + `create_tag_table.sql` + `create_ai_conversation.sql`（滤掉脚本里硬编码的 `create database`/`use`，避免打到真实库）→ 干净建出 **11 张表**（user / picture / space / space_user / post / post_like / post_comment / user_follow / user_notification / tag / ai_conversation），随后删除临时库。真实 `yu_picture` 未触碰。
5. **提交门禁**：`898c22e` 过 pre-commit——backend 单测绿、`ai/agent` **213** 绿、文档一致性 PASS。

## 本地无法验证的（交给 CI 首跑）

- GitHub service 容器的健康检查、`redis:5` 镜像可用性、`apt-get install mysql-client`。
- CI 首跑应绿；若红，按上表逐项排查：Init schema 步骤 → `application-ci.yaml` 占位 → 服务连通。
