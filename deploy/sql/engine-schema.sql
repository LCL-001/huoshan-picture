-- 引擎库（huoshan_ai_agent）建表脚本 —— 生产口径，2026-09-16 立
--
-- 背景：引擎不使用 Flyway（2026-09-16 用户拍板，理由见 docs/decisions/2026-09-16-flyway-removed.md），
-- 启动时不会建表，所以首次部署必须手工执行本脚本。
-- 内容取自本机长期在跑的 yu-ai-agent 库（show create table 原样导出，只加了
-- `if not exists` 与注释），引擎代码只碰这四张表：user / conversation / chat_message / chat_summary
-- （已逐列、逐索引与在跑的库核对一致）。
--
-- 用法（先建库，再在目标库内执行本脚本）：
--   CREATE DATABASE `huoshan_ai_agent` CHARACTER SET utf8mb4;
--   mysql -u root -p --default-character-set=utf8mb4 huoshan_ai_agent < deploy/sql/engine-schema.sql
--
-- 可重复执行：四张表都是 create table if not exists，索引内联在建表语句里。
-- 脚本自己带 `set names utf8mb4`，即使客户端默认字符集是 GBK 也不会踩"中文默认值报 1067"。
-- 另：ai/agent/create_sql/init.sql 供 docker-compose 初始化使用，数据库名与本清单保持一致；
-- 生产部署仍以本脚本为准。

set names utf8mb4;

create table if not exists `user`
(
    id          varchar(64)                           not null comment '用户ID'
        primary key,
    username    varchar(50)                            not null comment '用户名',
    password    varchar(64)                            not null comment '密码(MD5+salt)',
    user_role   varchar(20)  default 'user'           not null comment '角色: user/admin',
    create_time datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    is_delete   tinyint(1)   default 0                 not null comment '是否删除',
    constraint uk_username unique (username)
) comment '用户表';

create table if not exists `conversation`
(
    id          varchar(64)                           not null comment '会话ID'
        primary key,
    user_id     varchar(64)                           not null comment '所属用户ID',
    type        varchar(20)  default 'manus'          not null comment '会话类型: manus/love',
    title       varchar(200) default '新对话'         not null comment '会话标题',
    create_time datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    is_delete   tinyint(1)   default 0                 not null comment '是否删除',
    key fk_conversation_user (user_id),
    constraint fk_conversation_user foreign key (user_id) references `user` (id)
) comment '会话表';

create table if not exists `chat_message`
(
    id              bigint unsigned auto_increment comment '主键ID'
        primary key,
    conversation_id varchar(64)                          not null comment '会话ID',
    message_type    varchar(20)                          not null comment '消息类型',
    content         text                                 not null comment '消息内容',
    metadata        text                                 not null comment '元数据',
    create_time     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    is_delete       tinyint(1) default 0                 not null comment '是否删除 0-未删除 1-已删除',
    key idx_conversation_id (conversation_id)
) comment '聊天消息表';

-- 图库助手的历史消息不建 conversation 行（外部身份撞 fk_conversation_user），
-- 多轮记忆由无外键的 chat_message（按 conversation_id）与 chat_summary（按 chat_id）承载。
create table if not exists `chat_summary`
(
    id              bigint auto_increment comment '主键ID'
        primary key,
    chat_id         varchar(64) not null comment '会话ID',
    summary         text        null comment '会话摘要',
    last_message_id bigint      null comment '摘要覆盖的最后一条消息ID',
    constraint uk_chat_id unique (chat_id)
) comment '会话摘要表';
