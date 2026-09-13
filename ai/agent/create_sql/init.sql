drop database if exists `my-ai-agent`;
create database `my-ai-agent`;

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
)
    comment '用户表';

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
    is_delete       tinyint(1) default 0                 not null comment '是否删除 0-未删除 1-已删除'
)
    comment '聊天消息表';

create index idx_conversation_id
    on chat_message (conversation_id);

create table if not exists `conversation`
(
    id          varchar(64)                           not null comment '会话ID'
        primary key,
    user_id     varchar(64)                           not null comment '所属用户ID',
    title       varchar(200) default '新对话'         not null comment '会话标题',
    create_time datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    is_delete   tinyint(1)   default 0                 not null comment '是否删除',
    constraint fk_conversation_user foreign key (user_id) references `user` (id)
)
    comment '会话表';

-- 为 conversation 表添加 type 字段
ALTER TABLE `conversation`
    ADD COLUMN `type` VARCHAR(20) DEFAULT 'manus' NOT NULL COMMENT '会话类型: manus/love'
        AFTER `user_id`;

CREATE TABLE chat_summary (
                              id              BIGINT PRIMARY KEY AUTO_INCREMENT,
                              chat_id         VARCHAR(64) NOT NULL,
                              summary         TEXT,          -- 压缩出的摘要文本
                              last_message_id BIGINT,        -- 水位线：已压缩到哪条消息（含）
                              UNIQUE KEY uk_chat_id (chat_id) -- 一个会话一条摘要，重复压缩走更新
);