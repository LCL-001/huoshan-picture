# T69／T70 真实浏览器补验：用户恢复后的增量（2026-10-17）

> 前次证据保留不可变：`docs/evidence/2026-10-17-t68-t70-local-e2e.md`。本次用户明确“继续”，恢复失败轮次计算。仅修改验收脚本／证据，没有修改业务代码、表结构或既有 T69 工作文件。

## 本次结论

- **T69：本轮六组已执行断言通过，其中五组针对上传上下文、一组为真实 UI 登录前置。** 不能据此宣称全部 T69 场景通过；同上下文“详情待加载时上传”新风险尚未执行浏览器探针。
- **T70：仍未完成浏览器验收。** 已打开真实分享弹窗，核对其链接包含夹具图片 id，确认真实 canvas 元素存在；二维码像素断言误失败，未执行后续同人缓存、拒绝刷新清空、切号隔离和迟到私有列表回包组。
- 本次两轮连续未全绿，再次触发逃生舱，停止修正／重试；失败是验收脚本问题，不是已证实业务故障。下一步已明确，但没有擅自跑第三轮。
- T68 前次12/12真实HTTP通过的结论不变，本轮未重复创建T68夹具或复跑该脚本。

## 运行版本与服务

- 当前提交起点 `3b8a69d`，外加既有未提交 AddPicturePage.vue／上传上下文专项脚本，以及本次验收脚本变更。没有部署／推送。
- 复用前次已重建且包含 T68/T70 修复的 backend jar；SHA-256：`0DB2831621E9ABB4B693AEB2AF9FFC347C2595DE6EC17A4B3DD4C0AA3EBEA88D`。
- 实际 Chrome `154.0.8037.100`，本机已有 Playwright `1.64.0-alpha-1790635538000`。没有安装依赖，没有使用已有业务账号登录态。
- backend／Vite 均由本轮隐藏进程启动；真实 UI 登录使用新账号，邮箱码播种独立 Redis 测试键、图形答案仅读取本次 HTTP UUID，凭据不输出。空间由 HTTP 创建；已有图片为专用 SQL 合成元数据，不冒充上传链路。

## 上轮 SQL 失败已确定

受控诊断读取真实列定义：picture.url、thumbnailUrl 均为 varchar(512)。前次生成 PNG 为1793字节、data URL为2414字符，事务内插入仅指向前次独立测试账号／空间，MySQL返回：

```text
PNG_BYTES=1793 DATA_URL_CHARS=2414
thumbnailUrl 512
url 512
TRANSACTION_DIAGNOSTIC_STATUS=1 ERROR 1406 (22001) at line 1: Data too long for column 'url' at row 1
```

诊断没有提交业务行；成功路径显式 ROLLBACK，出错连接终止回滚。未执行 DELETE。脚本改为同尺寸、低熵合法 PNG：**105字节、data URL162字符**；先检查图片URL≤512和空间名≤30。没有扩大数据库字段或绕过业务校验。

## 恢复第1轮：4组通过，toast 阻挡后失败

原始脱敏输出见 `docs/evidence/2026-10-17-t69-t70-browser-resume1.txt`。

- 真实 UI 登录、A→新增空白、真实文件新增上传、A→B 四组通过。
- 点击“上传图片”进入延迟详情组时，成功 toast 覆盖导航，Playwright 的真实点击持续被拦截至20秒超时。不是强行点击后业务异常。
- 本机 AntD `es/vc-notification/Notice.js` 的 mouseenter 清除关闭计时、mouseleave 重启计时，鼠标停在导航位置可持续悬停重叠 toast。修正仅为在扣住响应**之前**把鼠标移到边角、等待 toast 自然消失；没有强制点击、删除 DOM 或修改业务提示。

## 恢复第2轮：六组通过

原始脱敏输出见 `docs/evidence/2026-10-17-t69-t70-browser-resume2.txt`。

| 组 | 实际断言 |
|---|---|
| 1 | 登录页面真实输入账号、密码和本次验证码；login/get-login HTTP建立 A 身份，非cookie注入 |
| 2 | 编辑 A→顶栏点击“上传图片”，同一个实际 AddPicturePage 实例，信息和文件／URL预览清空 |
| 3 | 实际 multipart 上传无旧id，backend业务0，真实COS上传并创建新DB行；A的id／名称／URL／空间／属主快照未改变 |
| 4 | 同一路由组件 A→B 查询参数切换，表单显示B，不残留A名称 |
| 5 | 扣住实际A详情响应，切到新增后释放原响应；浏览器同一Request实际requestfinished，响应体与后端原值相同，82ms＜8秒，新增仍为空 |
| 6 | A→当前空间新增，实际URL上传请求无旧id、spaceId正确；后端拒绝loopback URL，A数据库快照不变。不是有效公网URL成功导入证明 |

关键输出：

```text
FIXTURE: realUploadedPicture=2111428626808836097
PASS 3: T69 real multipart HTTP/COS upload has no old id; creates distinct DB row; A unchanged
DELIVERY: /api/picture/get/vo real response completed in browser; held=82ms (<8000); body unchanged
PASS 5: T69 delayed successful real HTTP picture-A reply ignored after A -> new
PASS 6: T69 actual URL-upload HTTP has no old id and correct space; backend rejects loopback; A unchanged
RESULT: FAIL phase=T70 real private page, Teleport share modal and QR canvas; page.waitForFunction: Timeout 20000ms exceeded.
```

上传前的安全守卫只阻断携带id的上传，避免错误替换；本轮成功请求不带id，实际发送后端／COS，不是模拟成功。跨路由延迟组只延迟真实HTTP响应交付，不更改响应体；不把它描述为完全无网络干预。

## QR 像素误失败：准确原因与停止点

失败截图在本地可见分享弹窗和二维码，但截图仅作辅助，不能替代脚本像素通过结论。源码独立复核确认：

1. `frontend/src/components/ShareModal.vue` 仅传 value，不传 bgColor。
2. 本机 `frontend/node_modules/ant-design-vue/es/qrcode/index.js` 默认 `bgColor = 'transparent'`；QRCode.js画布填透明背景，再画深色模块，不画不透明白色块。
3. 本次脚本先剔除 alpha＜200 的像素，却要求同时存在超过100个深色与浅色不透明像素。因此合法透明背景二维码可永远不满足条件。
4. Modal／Dialog 的实际源码类名传递支持 `.share-modal canvas` 的后代关系，没有发现结构性选择器错误。**未额外读取运行时像素统计，不编造 dark/light 数值。**

下一次恢复应只要求足够的不透明深色模块，并验证透明／非深色空白，或复制到测试用白底画布再检查对比；不要修改生产二维码背景来迁就测试。当前仍保留这个红断言，**没有在停止后改断言并偷跑**。

## 脚本可信度加固与未执行部分

本次独立review指出并已在第二轮前加固：SPA首页等待路由动画完成／空间根移出；denied组要求空间根存在且UID相同；延迟组绑定实际Request的response/requestfinished并核对原响应与8秒门槛；B在迟到私有回包后重进A空间再验拒绝与新UID，避免“只看首页没泄漏”的假阳性。

其中**只有T69延迟详情实际执行通过**；T70对应组未执行，不因脚本已写好而记为通过。同样，新增 `BROWSER_E2E_PENDING_DETAIL_PROBE=1` 是待运行的独立竞态入口，尚无真实浏览器结果。它只新增上传、不执行保存或替换原图；本轮未运行，不能引用其预期输出当证据。

## 复跑入口（目前仍会红）

```powershell
# 先启动当前backend:8123及frontend:5173，并修正上述已明确的QR测试断言。
$env:PLAYWRIGHT_MODULE='<本机已有 playwright 包的绝对目录>'
node docs/evidence/2026-10-17-t69-t70-browser-e2e.cjs

# 尚未执行的详情/上传竞态探针；结束后应移除该环境变量再跑常规路径。
$env:BROWSER_E2E_PENDING_DETAIL_PROBE='1'
node docs/evidence/2026-10-17-t69-t70-browser-e2e.cjs
```

脚本仍非绿色验收门禁。运行时需Chrome、mysql/redis-cli、当前YAML配置；仅loopback。真实上传会写少量COS资产，不自动清理。

## 本轮残留与恢复

- 两轮fixture分别为 `e2e_browser_8c0b77e6`、`e2e_browser_87490128`；账号／空间／图片id见各原始输出。四个新账号、两个私有空间、四条合成图片记录、两条实际上传图片记录及对应COS资产保留。没有枚举或删除既有资产；前次残留不在本轮清理范围。
- 未手工删除文件、数据库行、Redis键或COS对象；认证与验证码键按TTL管理。应用上传内部自带临时文件生命周期，不把“不手工删除”夸大为整个操作系统没有任何删除。
- 停止前核对backend PID52068、Vite PID31000的进程名和命令行后关闭；结束8123／8124／8125／5173无监听，原有3306／6379保持。自动化Chrome上下文由脚本finally关闭。
- 本地截图 `backend/target/e2e_browser_87490128-browser-failure.png` 与target辅助日志**不可复跑，证据已过期**，不入库；永久脚本／脱敏文本输出／本报告构成复现入口，不把该临时截图路径当唯一证据。
- 原未跟踪 `.serena/`、`.spec-workflow/`、`throughput` 及T69两个工作文件未回滚／删除。spec三行不变，AI引擎未启动，没有模型调用、部署或推送。