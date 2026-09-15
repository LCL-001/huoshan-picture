# 存量遗留（未做，未拍板）

> 自 docs/plan.md 外移（2026-09-15），内容逐字保留。

- **前端社交模块残留**（2026-09-15 排查 `frontend/src/api/` 时发现）：**2026-09-15 T22 已收口**（用户拍板"留下通知即可"）——三个路由与三个页面删除、`postController.ts` 整体删除（6 个通知函数先迁入 `api/notificationController.ts`，`GlobalHeader.vue` 改指新文件），下面保留原排查记录：后端 `Post` / `PostInteraction` / `UserFollow` 相关 controller 的类注解已注释、Spring 不加载（见 spec 的 F7），但**前端还在用**——路由里 `/square`、`/post/:id`、`/user/:id` 三个页面照旧注册着，`SquarePage.vue` / `PostDetailPage.vue` / `UserProfilePage.vue` / `GlobalHeader.vue` 四个文件引用 `@/api/postController.ts`，其中帖子/点赞/关注那批函数打的是**已停用**的 `/post/**`（用户点进去大概率失败）。
  - **关键约束（别整文件删）**：`postController.ts` 是**混装**文件——第 163 行起那批 `/api/notification/**` 函数（`listNotifications` / `getUnreadCount` / `markNotificationRead` / `deleteNotification` / `clearNotifications` / `clearUnread`）是**手工追加**进这个生成文件的（签名 `options?: any` 与生成器风格不一致），而它们**仍在服务**：顶栏铃铛 `GlobalHeader.vue` 用的正是这 5 个。要清理必须先把通知函数迁到独立文件（如 `notificationController.ts`），再动页面与路由。
  - 同批发现：`frontend/src/api/index.ts`（生成器的聚合出口）全站**零引用**，是死文件；`frontend/src/api/*.ts` 整体是"生成产物 + 手工改造 + 全手写文件（`assistantController.ts` / `authCaptcha.ts`）"的混合体，**不可重跑 `npm run openapi`**（机制见 `decisions.md` 2026-09-15 行）。
  - 处置：**未拍板**。清理要删页面/路由并迁移通知调用，属行为变更，按规则 1/3 需先立字据。
