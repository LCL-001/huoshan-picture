-- AI 助手会话索引（docs/plan.md T36，设计见 docs/designs/2026-09-28-助手快赢包设计.md）
-- 只记"谁发起过哪些对话"：标题取首条消息截断；消息体本身仍在引擎 chat_message，本表不冗余
use yu_picture;

SET NAMES utf8mb4;

create table if not exists ai_conversation
(
    id         bigint auto_increment comment 'id' primary key,
    userId     bigint                             not null comment '图库用户 id',
    chatId     varchar(64)                        not null comment '前端对话串标识（crypto.randomUUID，36 字符）',
    title      varchar(256)                       not null comment '会话标题（首条消息截断 64 字，后续不覆盖）',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_user_chat (userId, chatId),
    INDEX idx_user_update (userId, updateTime)
) comment 'AI 助手会话索引' collate = utf8mb4_unicode_ci;
