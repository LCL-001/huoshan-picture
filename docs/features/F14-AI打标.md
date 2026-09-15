# F14 AI 打标（管理员手动触发）

> 覆盖任务：docs/plan.md **T15**（图库侧 AI 打标服务）。**前端入口是 T16（未做）**，助手侧工具裁剪是 T17（未做）。
> 口径：`docs/decisions.md` 2026-09-15 三条；为什么不等同于"对话里打标"（设计文档 L58）见「关键设计与理由」1/3。

## 一句话

管理员挑几张（公共图库里没有标签的）图片 → 图库服务端**自己**调多模态模型看图 → 先出标签/分类**建议** → 管理员确认后按批写库。**只写 `tags` 与 `category`**：审核状态、审核人、审核留言、审核时间一律不动。

## 怎么用

前端入口还没做（T16），当前只有两个接口（都要求管理员登录，普通用户 `40300`）：

| 接口 | 入参 | 作用 |
|---|---|---|
| `POST /api/picture/ai_tag/suggest` | `{"pictureIdList": ["<图 id>", …]}` | 逐张看图出建议，**不写库**；单次最多 8 张 |
| `POST /api/picture/ai_tag/apply` | `{"items":[{"pictureId":…,"tags":[…],"category":"…"}]}` | 把（管理员改过的）建议写库；空值表示"这项不改" |

两类结果都返回逐张的 `{pictureId, url, ok, tags[], category, message}`：`ok=false` 的那张带原因（超时/看图失败/没有图片地址），**不影响同一批里的其它张**。

配置（`application.yaml` 的 `app.ai.vision.*`，密钥走环境变量不入库）：`base-url`（**协议根**，不要带 `/v1`）、`api-key`、`model`（例 `mimo-v2.5`）、`timeout-ms`（默认 45000）、`concurrency`（默认 4）、`max-per-request`（默认 8）。三项缺一，接口直接拒绝并给出"请设置 app.ai.vision…"的文案（fail-closed）。

## 怎么实现

### 关键文件

| 文件 | 作用 |
|---|---|
| `api/vision/AiVisionTagApi` | OpenAI 兼容看图调用：`{base-url}/v1/chat/completions` + `Bearer`，一条 user 消息里带文本与 `image_url` 两段；超时；出错文案**只带状态码、不带厂商响应体原文** |
| `manager/ai/PictureAiTagManager` | 提示词、**有界并发（层次一）**、单张独立计时、容错解析、输入清洗、以及"只改标签与分类"的更新条件组装 |
| `service/impl/PictureServiceImpl#suggestAiTags/#applyAiTags` | 取图与词表、落库、词表 `usageCount`、清列表缓存 |
| `controller/PictureController#suggestAiTags/#applyAiTags` | 两个 `@AuthCheck(mustRole = ADMIN_ROLE)` 端点 |
| `domain/vo/PictureAiTagSuggestionVO` | 逐张结果（出建议与应用复用同一结构） |
| `domain/dto/picture/PictureAiTagRequest`、`PictureAiTagApplyRequest` | 两个入参 DTO |

### 核心流程

```
suggest：校验（非空 / ≤ max-per-request / 图都存在，按请求顺序）→ 读词表 → 提示词
         → 每请求一个有界池（concurrency 个守护线程）并发看图
         → 按下标收集 future.get(timeout)：超时/异常 ⇒ 该张降级
         → 返回建议列表（**不写库**）

apply  ：校验 → 载入图片（按 id）→ 逐条清洗（转义 HTML / 限长）
         → LambdaUpdateWrapper **只 set tags 与 category**（空值不 set）→ 逐张 update
         → 本批标签/分类去重后各 upsert 一次词表 → 清图片列表缓存 → 返回逐条结果
```

### 关键设计与理由

1. **为什么落在图库侧、而不是助手的工具**：公共图库的图 `spaceId` 为 NULL，助手唯一的写标签工具 `batchEditPictures` 走的 `/picture/edit/batch` **按 `spaceId` 查图且强制空间属主**（`PictureServiceImpl:1145-1154`），根本覆盖不到；而单张 `editPicture`（`:921`）会调 `fillReviewParams`（`:587-599`）——普通用户身份会把图**打回待审核**（于是从公共图库消失），管理员身份会把 `reviewerId`/`reviewMessage` 覆盖成"管理员自动过审"。所以必须新增一个专用的"只改标签与分类"的服务端方法。
2. **"只改标签与分类"是结构性保证，不是约定**：更新条件由 `PictureAiTagManager.applyTo` 组装，SET 子句里只有这两列；单测直接断言 `getSqlSet()` 不含 `review_status`/`reviewer_id`/`review_message`/`review_time`，集成测试再用真库逐字段核对写完之后的值没变。这样发布/审核链路与 AI 打标彻底解耦。
3. **两步式（先建议、确认后落库）**：公共图库是访客看的门面，写错标签的影响面比空间图大；而且"建议—确认"本就是设计文档 L58 的口径（对话场景里也是先讲建议），沿用不必新立决策。**与对话场景的区别**：那边由用户在对话里确认，这边由管理员在管理端确认；因此"自动落库"只发生在管理员按下确认之后，不存在无人确认的写入。
4. **并发＝"层次一"**（2026-09-15 用户拍板）：MiMo 单张看图 10~15s，8 张串行要一两分钟；改有界并发后约 30~40s。三条约束——每请求独立有界池（**不用公共 `ForkJoinPool`**，阻塞 IO 占满公共池会殃及全 JVM）、**单张独立计时**（一张挂死不拖垮整批）、**结果按输入顺序落位**（管理页要按图核对，不能随完成先后乱序）。
5. **空值不写**：分类为空串、标签为空数组时不动原值（与 `editPictureByBatch` 的"空=不改"同口径），避免"模型没给出分类"把已有分类清掉。
6. **`usageCount` 按批去重后各计一次**：与 `editPictureByBatch` 的"不随图片数放大"一致——一次打标 20 张都用了"高清"，词表只 +1。
7. **管理员限定落在一处**：图库接口 `@AuthCheck(mustRole = ADMIN_ROLE)` + 一条反射守护测试（防止有人把注解删了而没人发现）。引擎侧的工具裁剪与前端入口可见性属 T17/T16。
8. **模型配置 fail-closed**：三项缺一就在入口拒绝，而不是发满 N 次注定失败的请求（与引擎侧 `hasAssistant()` 的同一手法）。

## 怎么验证

- **门禁**：backend **48 → 70 例**绿（新增 22：管理器 16 + 看图接口 4 + 管理员守护 2）。
- **并发的四条契约**（`PictureAiTagManagerTest$Concurrency`）：有界并发（6 张 / 并发 3 ⇒ 峰值并发**恰为 3**）、结果保序（第 1 张最慢也仍在第 1 位）、单张超时只降级该张、单张失败不连坐；另有"没有图片地址的图不发模型调用"。
- **请求形态**（`AiVisionTagApiTest`，进程内 JDK HttpServer 桩）：路径 `/v1/chat/completions`、`Bearer`、`model`、`content=[text, image_url]`；非 2xx 抛业务异常且**文案里不含厂商响应体原文**；未配置时一个请求都不发。
- **审核字段不变量**：单测断言 SET 子句（见上）；**集成测试**（`PictureAiTagIntegrationTest`，本机 MySQL + 桩模型）用真库核对——① 出建议零写库；② 应用后 `tags`/`category` 已写；③ `reviewStatus`/`reviewerId`/`reviewMessage`/`reviewTime` **逐字段与写入前相同**；④ 词表 `usageCount` 各 +1。测试自建唯一词条并**硬删**收尾（`picture` 有 `@TableLogic`，用 SQL 物理删），不留探针数据。
- **负向控制**（规则 12）：把并发池临时改成 1 → "峰值并发恰为 3"**红**（`expected: 3`）；摘掉 `applyAiTags` 的 `@AuthCheck` → 守护测试**红**。两者改回后全绿。

## 已知限制

1. **前端入口未做（T16）**：管理页还没有"AI 打标"按钮与建议确认弹窗，当前只能用接口。
2. **助手侧未收口（T17）**：普通用户的助手仍挂着只读 `visionTagger`——2026-09-15 已定"只管理员可用"，实现落在 T17。
3. **提示词有两份**（引擎 `VisionTaggerTool.PROMPT_TEMPLATE` 与 `PictureAiTagManager.PROMPT_TEMPLATE`）：刻意如此——本功能不依赖引擎进程与用户凭据；若 T17 把助手工具改为调本接口，引擎那份即可删除。
4. **接口不限定 `spaceId`**：管理员对空间图同样有编辑权，故未加"只允许公共图库"的硬校验；"只对公共图库用"由管理页入口与筛选保证（若产品上要收紧，加一条 `spaceId IS NULL` 校验即可）。
5. **单次上限 8 张**（与引擎工具同口径）：真遇到大批量要分批；若某次请求耗时过长，前端表现为"请求超时而后端仍在跑"（写库最终会完成）——T16 实现前端时要单独放宽该接口的超时并提示分批。
6. **并发度是每请求的**：多个管理员同时打标时总并发 = 4 × 请求数；厂商侧的并发限流未知，真撞上表现为该批大量失败（本功能**没有接重试**，引擎侧那套重试模板不在本链路）。
7. **没有每日配额**（扩图有 `app.outpainting.daily-quota`）：管理员手动触发天然低频 + 单次上限已拦；将来调用量变大再补。
8. **图片地址必须厂商可抓取**：图库 COS 地址实测可用；第三方 CDN 曾被 DeepSeek 拒（同类问题见 F12 已知限制 7），换厂商/换图源要重测。
