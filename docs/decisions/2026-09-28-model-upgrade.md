# 2026-09-28 助手模型升级为 mimo-v2.6-flash

> 表内一行见 `docs/decisions.md` 2026-09-28 行；本条是完整原文（写定后不可变，勘误除外）。
> 被本行取代的旧口径是 `docs/decisions/2026-09-15-topic02.md`（模型换成 MiMo `mimo-v2.5`）——该详情文件按规则不可变，故不回改。

## 一句话

图库助手的主脑与 visionTagger 型号，从 `mimo-v2.5` 换成 `mimo-v2.6-flash`（用户 2026-09-28 指定），**主脑与 vision 两组同换**；base-url 与"显式关思考模式"两条既有口径不变，**零代码改动**。

## 为什么

- **旧口径里"图像输入仅 mimo-v2.5 支持"已经失效**：官方"图片理解"页的支持列表写明"当前支持 `mimo-v2.6-flash`、`mimo-v2.6-pro`、`mimo-v2.6-pro-ultraspeed` 和 `mimo-v2.5`"，且 `mimo-v2.6-flash` 的模型规格写明输入模态是**文本、图像、视频、音频**（1M 上下文）。这正是 2026-09-15 那条口径当年把主脑与 vision 一起钉在 `mimo-v2.5` 的原因，现在理由不成立了，所以视觉那组可以同换。
- **协议面不用动**：官方示例仍是 `base_url = https://api.xiaomimimo.com/v1`（引擎配置里的 base-url 取协议根、不带 `/v1`，口径不变），仍是 `extra_body = {"thinking": {"type": "disabled"}}`（顶层 `thinking` 字段不变，`app.ai.openai.*.extra-body` 的直通照旧），鉴权仍走 Spring AI 默认的 `Authorization: Bearer`。

## 改了什么

| 文件 | 入库 | 改动 |
|---|---|---|
| `ai/agent/src/main/resources/application-local.yaml` | 否 | 主脑与 vision 两处 `model`；顺带订正"图像输入仅 v2.5 支持"的注释 |
| `ai/agent/src/main/resources/application-prod.yaml` | 否 | 同两处 |
| `backend/src/main/resources/application-local.yaml` | 否 | `app.ai.vision.model`（AI 打标那条链路） |
| `ai/agent/src/main/resources/application-prod.yaml.example` | 是 | `${AI_OPENAI_MODEL:…}` / `${AI_OPENAI_VISION_MODEL:…}` 的默认值 |
| `ai/agent/.env.example` | 是 | `AI_OPENAI_MODEL` / `AI_OPENAI_VISION_MODEL` 的示例值 |

**线上 prod 不在本次范围**：prod 的型号由服务器环境变量 `AI_OPENAI_MODEL` / `AI_OPENAI_VISION_MODEL` 决定，本次只改**入库默认值**；对"环境变量已设"的 prod 行为无影响。线上要换须改环境变量并按 `deploy/prod-checklist.md` 重启引擎。

## 没改什么（有意为之）

- 两处单测里的 `mimo-v2.5` 字面量（`FlowWindowBasedChatMemorySummaryModelTest:146`、`AiVisionTagApiTest:33`）是测试夹具——不读生产配置、不构成行为依赖，改它只是制造无意义的 diff，故保留。
- `docs/decisions/2026-09-15-topic02.md`、`docs/decisions/2026-09-16-dashscope-decoupling.md`、`docs/plans/records/**`、`docs/handoff/**` 里的 `mimo-v2.5`：这些是"写定后不可变"的历史事实（被取代时期的记录），不回填；`docs/features/F12` 里带日期的真机记录同理。

## 代价与待验证

- **本次没有真机冒烟**：现有全部真机证据（带工具的多轮调用、两条业务场景、看图建议、搜图入库）都是在 `mimo-v2.5` 上取得的，换型号后**需要重跑才算同口径验收**。参考 `F12` 已知限制 7 的口径：路径/协议相关的兼容性在换型号或换厂商后必须重测。
- **验收点**：起引擎后日志应出现 `OpenAI 协议模型就绪：config=app.ai.openai.assistant, baseUrl=https://api.xiaomimimo.com, model=mimo-v2.6-flash ... extraBodyKeys=[thinking]`（`deploy/prod-checklist.md` §3 同款写法）；再问一句会触发工具调用的话，确认 `tool` 帧照常产出、无 `error` 帧。
- **速度对比未知**：2026-09-15 实测 `mimo-v2.5` 明显慢于 deepseek-flash（同一整理场景 53.4s vs 12.6s）。`-flash` 定位是高效推理、预期更快，但**本次没有实测数字**，不做承诺。

## 证据

- 厂商文档（2026-09-28 读）：`https://mimo.mi.com/models/zh-CN/mimo-v2.6-flash`（规格：输入模态文本、图像、视频、音频）；`https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/multimodal-understanding/image-understanding`（"支持的模型列表"含 `mimo-v2.6-flash`）。
- 配置改动与提交链：见 `docs/plans/records/T35-模型升级.md`。
