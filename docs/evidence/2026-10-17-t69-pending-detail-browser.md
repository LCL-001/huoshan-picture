# T69 同上下文详情／上传竞态——真实浏览器独立复现

日期：2026-10-17。结论：**P2 已在真实 Chrome／本地 HTTP 复现；exit 2 表示业务回归被证实，不是夹具失败。** 本次未改业务代码或共享验收脚本，未重试或尝试修成绿。

## 可复跑入口

前置：最新本地 backend `http://localhost:8123/api`、frontend `http://localhost:5173` 已启动；本地 MySQL／Redis 可用；已有 Playwright 包和 Chrome。运行专用随机账号及小图夹具，不使用真实用户。脚本会实际创建上传记录及 COS 对象，**不替换已有图片，不执行编辑／保存，不清理数据**。

仓库根目录 PowerShell：

```powershell
$env:PLAYWRIGHT_MODULE='D:/npm-cache/_npx/9833c18b2d85bc59/node_modules/playwright'
$env:BROWSER_E2E_PENDING_DETAIL_PROBE='1'
node docs/evidence/2026-10-17-t69-t70-browser-e2e.cjs
```

可复跑脚本：`docs/evidence/2026-10-17-t69-t70-browser-e2e.cjs`。本次原始脱敏控制台输出：`docs/evidence/2026-10-17-t69-pending-detail-browser-output.txt`。

## 独立只读复核与实际步骤

1. 先只读核查探针：以真实 UI 验证码／登录建立身份；先显示 B，然后切入 A 编辑上下文并扣留真实成功 `/api/picture/get/vo?id=<A>` 回包；未修改响应体。
2. 详情尚未交付时，等待上传区及表单为空，通过真实文件 input 上传 105 字节 PNG。上传安全守卫会拦截任何携带旧 id 的上传，避免原图替换。
3. 实际 `/api/picture/upload` 返回 `code=0`、新图片 id，等待输入框显示该真实上传成功结果；确认新行存在。
4. 保持路由上下文不变，释放原始成功 A 详情。释放前注册浏览器 response 和 requestfinished 监听；确认 HTTP 200、请求成功完成、JSON 与真实后端原响应一致，扣留时间小于 8 秒，排除 Axios 10 秒超时造成的假阴性。
5. 详情交付后，表单名称变回 A；这符合已知竞态：同一路由 contextVersion 未改变，迟到详情覆盖刚成功上传的 picture／表单。
6. 原图 A 的 `id,name,url,spaceId,userId` 数据库快照前后相同；没有执行编辑／保存。此处不是全列数据库审计，也不声称已证明进一步错误保存的影响。

探针在看到上传成功后才释放详情；上传失败、详情错误／交付失败／超过 8 秒、原图快照改变、浏览器异常均为失败而非该 P2。没有发现会将这些路径算作 P2 的明显假阳性条件。

## 本次实际证据

- Chrome `154.0.8037.100`，Playwright `1.64.0-alpha-1790635538000`。
- 独立 fixture：`e2e_browser_a2dc5864`。
- A 账号：`e2e_browser_a2dc5864_a`，userId `2111431054950227970`。
- B 账号：`e2e_browser_a2dc5864_b`，userId `2111431055596150786`。
- A 私有空间：`2111431067776409602`。
- 合成图片 A：`2111430893473718274`；合成图片 B：`2111430893473718275`。
- 实际上传新图：`2111431075917553666`；上传 `business=0`，请求没有旧 id，上传结果曾在表单显示。
- 原始 A 详情浏览器交付：`/api/picture/get/vo`，**held=1233ms (<8000)**，成功 requestfinished，响应体未改写。
- 最终：表单退回 A，输出 `REGRESSION: P2 confirmed in real Chrome/HTTP`，进程 **exit 2**。

## 残留与边界

保留两个独立账号、一个私有空间、两张合成元数据图片、一张实际上传记录及其 COS 资产；认证会话和验证码／频控按原应用 TTL 管理。不打印密码、token 或验证码。未执行删除、清理或错误 save，未启停服务，未调用模型或发送真实邮件。仅本地 Chrome＋直连本地 HTTP，不覆盖生产 nginx／TLS；本报告不是 T69/T70 全套通过结论。退出时脚本关闭自身测试浏览器，backend／Vite／MySQL／Redis 保持原状。