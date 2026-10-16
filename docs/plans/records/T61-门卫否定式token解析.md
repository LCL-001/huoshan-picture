# T61 — 门卫否定式 IN_SCOPE 解析（双向）

> 2026-10-16。来自两日改动 review 修复批次（plan.md T61，P2）；功能讲解同步在 `docs/features/F11-AI助手MVP.md`（已知限制 14 与验证节），
> 设计沿革见 `docs/designs/2026-10-12-门卫召回第二轮设计.md` 的 A3'（T46 只做了单向守卫，本任务把它做成双向）。
> 本文件只记实施过程、红绿输出、负向控制与证据。代码 commit `71f7a3b`。

## 范围

- 修改：引擎 `ai/agent/.../agent/HuoshanAssistantAgent.java`——`isInScopeDecision` 的否定词处理改为**翻转**语义（不再只是"跳过被否定的 OUT_OF_SCOPE"）；
  `SCOPE_NEGATION_PATTERN` 的注释从"紧邻出现在 OUT_OF_SCOPE 前方"改为"**任一**范围 token 前方"。
- 测试：`ai/agent/.../agent/HuoshanAssistantAgentScopeTest.java`——新增 5 组断言（两个参数化 + 一个含糊输出守卫 + 两个端到端）。
- 未动：门卫输入窗口（T46 A1'）、`SCOPE_PROMPT`/`SCOPE_CAPABILITIES_EN`、拒绝话术、`BaseAgent` 循环、backend/前端。

## 病根

T46 A3' 的"否定式前言守卫"只作用在 `OUT_OF_SCOPE` 上：命中 `OUT_OF_SCOPE` 且前方有否定词 → **跳过**、继续找下一个 token；
而命中 `IN_SCOPE` 时**无条件**返回放行。于是：

| 模型输出 | 改前 | 应为 |
|---|---|---|
| `not IN_SCOPE` | **放行（洞）** | 拒绝 |
| `not IN_SCOPE，虽然后面写着 IN_SCOPE` | **放行（洞）** | 拒绝 |
| `not OUT_OF_SCOPE` | 拒（跳过 → 再无 token → fail-closed） | 放行 |
| `OUT_OF_SCOPE…IN_SCOPE` | 拒 | 拒（不变） |
| 无 token（含糊/解释性/空输出） | 拒 | 拒（不变） |

第一行是**安全面**的洞：分类器说"这不属于范围内"，解析却因为先看到 `IN_SCOPE` 字面量就放行，把无关请求放进 ReAct 工具链。
第二行是召回面的损失：明确的"不是范围外"反而被拒。两条都源于"否定词 = 跳过"这个单向实现。

## 修复

把"跳过"改成"**翻转**"——首个 token 定生死（T43 不变），否定词把它修饰的那个 token 的语义反过来：

```java
if (matcher.find()) {
    boolean inScopeToken = IN_SCOPE.equals(matcher.group());
    // 否定词把"是不是范围内"反过来：not IN_SCOPE = 范围外，not OUT_OF_SCOPE = 范围内
    return isNegated(text, matcher.start()) ? !inScopeToken : inScopeToken;
}
return false;   // 没 token：含糊/解释性/空输出一律 fail-closed
```

- T46 A3' 的"否定式前言"由此**统一表达**（`not OUT_OF_SCOPE` → 范围内），不再是特例分支。
- 仍然只看**第一个** token（`OUT_OF_SCOPE…IN_SCOPE` 依旧拒，T43 口径不动）；否定词回看窗口仍为 16 字符、词表不变。

## 红绿输出（可复跑）

```
cd ai/agent && mvn -B test -Dtest=HuoshanAssistantAgentScopeTest -DfailIfNoTests=false
```

- **先红**：`Tests run: 26, Failures: 10`，恰为本次新增断言——
  `negatedInScopeIsTreatedAsOutOfScope` 5 例（参数化）、`negatedOutOfScopeIsTreatedAsInScope` 3 例（第 2 例 `This is not OUT_OF_SCOPE; it is IN_SCOPE.`
  改前即绿——T46 已覆盖该形态）、`negatedInScopeRejectsWithoutEnteringToolChain`、`negatedOutOfScopeAllowsToolChain`。
  同批 `negationWithoutAnyScopeTokenStaysFailClosed` 改前即绿（它守的是"含糊即拒"，本就不该红）。
- **后绿**：同命令 `26/26`。
- **负向控制（规则 12）**：保留新实现，只把 `IN_SCOPE` 那一侧的翻转去掉（回到"IN_SCOPE 无条件放行"）→
  `Tests run: 26, Failures: 6`，**恰好是被否定的 IN_SCOPE 那 6 例**（5 个参数化 + 1 个端到端）；还原即绿。
  即：本次修复的安全面被这些测试精确钉住。
- **全量门禁（pre-commit 口径）**：`ai/agent` **235/235**（改前 223，本轮 +12 例）；提交时 pre-commit 实跑 backend **185/185**、引擎 **235/235**。

## 端到端口径（拒绝路径不进工具链）

`negatedInScopeRejectsWithoutEnteringToolChain`：门卫模型回 `not IN_SCOPE` →
· 回答 = `OUT_OF_SCOPE_REPLY`（固定话术）；· `mainModelCalls() == 0`（正式助手一次都没跑）；
· `listener.count(Step) == 0`（无任何 step 帧）；· `Done` 恰好 1；· `toolManager.executeToolCalls` 从未被调用。
反向 `negatedOutOfScopeAllowsToolChain`：回 `This is not OUT_OF_SCOPE.` → 正常走主模型（2 次调用）与 `listSpaces` 工具链，回答"完成"。

## 已知限制 / 未覆盖

1. **否定词表仍是固定几种**：`not` / `isn't` / `is not` / `非` / `不是` / `不属于`（回看 16 字符）。
   表外的否定说法（如 "hardly IN_SCOPE"、其它语言的否定词）**识别不出**，会按字面 token 的极性走 ——
   往"放行"方向漏（与门卫"语义策略、非授权边界"的定位同族，见 F11 已知限制 14），本轮不进词表。
2. **首个 token 定生死**不变：`not IN_SCOPE. IN_SCOPE` 判拒（与 T43 "第一个命中的 token 必须是 IN_SCOPE" 同一口径），
   没有改成"找第一个有效极性"。
3. **只修解析、不动分类**：分类器本身把无关请求判成 `IN_SCOPE` 的情形照旧放行——那是 T25 划定的语义策略边界（不是授权边界），
   本轮不碰；`SCOPE_PROMPT` / 能力枚举也未动。
4. **未做真机复跑**：本轮证据 = 单测（含红/绿/负向控制），未在真实 MiMo 上重跑门卫分类（T46 的真机口径未复测）。
