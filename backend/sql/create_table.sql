# 建库
create database if not exists yu_picture;

use yu_picture;

# 建表
-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 图片表
create table if not exists picture
(
    id           bigint auto_increment comment 'id' primary key,
    url          varchar(512)                       not null comment '图片 url',
    name         varchar(128)                       not null comment '图片名称',
    introduction varchar(512)                       null comment '简介',
    category     varchar(64)                        null comment '分类',
    tags         varchar(512)                      null comment '标签（JSON 数组）',
    picSize      bigint                             null comment '图片体积',
    picWidth     int                                null comment '图片宽度',
    picHeight    int                                null comment '图片高度',
    picScale     double                             null comment '图片宽高比例',
    picFormat    varchar(32)                        null comment '图片格式',
    userId       bigint                             not null comment '创建用户 id',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    INDEX idx_name (name),                 -- 提升基于图片名称的查询性能
    INDEX idx_introduction (introduction), -- 用于模糊搜索图片简介
    INDEX idx_category (category),         -- 提升基于分类的查询性能
    INDEX idx_tags (tags),                 -- 提升基于标签的查询性能
    INDEX idx_userId (userId)              -- 提升基于用户 ID 的查询性能
) comment '图片' collate = utf8mb4_unicode_ci;

ALTER TABLE `user` ADD COLUMN `email` varchar(128) NULL DEFAULT NULL COMMENT '邮箱' AFTER `userAccount`;
ALTER TABLE `user` ADD UNIQUE INDEX `uk_user_email` (`email`);

ALTER TABLE picture
    -- 添加新列
    ADD COLUMN reviewStatus INT DEFAULT 0 NOT NULL COMMENT '审核状态：0-待审核; 1-通过; 2-拒绝',
    ADD COLUMN reviewMessage VARCHAR(512) NULL COMMENT '审核信息',
    ADD COLUMN reviewerId BIGINT NULL COMMENT '审核人 ID',
    ADD COLUMN reviewTime DATETIME NULL COMMENT '审核时间';

-- 创建基于 reviewStatus 列的索引
CREATE INDEX idx_reviewStatus ON picture (reviewStatus);

ALTER TABLE picture
    -- 添加新列
    ADD COLUMN thumbnailUrl varchar(512) NULL COMMENT '缩略图 url';

-- 空间表
create table if not exists space
(
    id         bigint auto_increment comment 'id' primary key,
    spaceName  varchar(128)                       null comment '空间名称',
    spaceLevel int      default 0                 null comment '空间级别：0-普通版 1-专业版 2-旗舰版',
    maxSize    bigint   default 0                 null comment '空间图片的最大总大小',
    maxCount   bigint   default 0                 null comment '空间图片的最大数量',
    totalSize  bigint   default 0                 null comment '当前空间下图片的总大小',
    totalCount bigint   default 0                 null comment '当前空间下的图片数量',
    userId     bigint                             not null comment '创建用户 id',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    editTime   datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    -- 索引设计
    index idx_userId (userId),        -- 提升基于用户的查询效率
    index idx_spaceName (spaceName),  -- 提升基于空间名称的查询效率
    index idx_spaceLevel (spaceLevel) -- 提升按空间级别查询的效率
) comment '空间' collate = utf8mb4_unicode_ci;

-- 添加新列
ALTER TABLE picture
    ADD COLUMN spaceId bigint null comment '空间 id（为空表示公共空间）';

-- 创建索引
CREATE INDEX idx_spaceId ON picture (spaceId);

ALTER TABLE picture
    ADD COLUMN picColor varchar(16) null comment '图片主色调';

ALTER TABLE space
    ADD COLUMN spaceType int default 0 not null comment '空间类型：0-私有 1-团队';

CREATE INDEX idx_spaceType ON space (spaceType);


-- 空间成员表
create table if not exists space_user
(
    id         bigint auto_increment comment 'id' primary key,
    spaceId    bigint                                 not null comment '空间 id',
    userId     bigint                                 not null comment '用户 id',
    spaceRole  varchar(128) default 'viewer'          null comment '空间角色：viewer/editor/admin',
    createTime datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    -- 索引设计
    UNIQUE KEY uk_spaceId_userId (spaceId, userId), -- 唯一索引，用户在一个空间中只能有一个角色
    INDEX idx_spaceId (spaceId),                    -- 提升按空间查询的性能
    INDEX idx_userId (userId)                       -- 提升按用户查询的性能
) comment '空间用户关联' collate = utf8mb4_unicode_ci;

-- 论坛帖子表
create table if not exists post
(
    id           bigint auto_increment comment 'id' primary key,
    userId       bigint                             not null comment '作者 id',
    title        varchar(255)                       not null comment '标题',
    content      text                               not null comment '正文内容',
    images       varchar(2048)                      null comment '图片列表，逗号分隔，最多9张',
    tags         varchar(512)                       null comment '标签，逗号分隔',
    likeCount    int       default 0                not null comment '点赞数',
    commentCount int       default 0                not null comment '评论数',
    viewCount    int       default 0                not null comment '浏览数',
    status       tinyint   default 0                not null comment '状态：0-正常 1-隐藏',
    createTime   datetime  default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime  default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint   default 0                not null comment '是否删除',
    INDEX idx_userId (userId),
    INDEX idx_createTime (createTime)
) comment '论坛帖子' collate = utf8mb4_unicode_ci;

-- 帖子点赞表
create table if not exists post_like
(
    id         bigint auto_increment comment 'id' primary key,
    postId     bigint                             not null comment '帖子 id',
    userId     bigint                             not null comment '用户 id',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    UNIQUE KEY uk_postId_userId (postId, userId),
    INDEX idx_postId (postId),
    INDEX idx_userId (userId)
) comment '帖子点赞' collate = utf8mb4_unicode_ci;

-- 帖子评论表
create table if not exists post_comment
(
    id             bigint auto_increment comment 'id' primary key,
    userId         bigint                             not null comment '评论用户 id',
    postId         bigint                             not null comment '帖子 id',
    parentId       bigint   default 0                 not null comment '父评论 id，0=一级评论',
    replyToUserId  bigint                             null comment '被回复的用户 id',
    content        varchar(500)                       not null comment '评论内容',
    likeCount      int      default 0                 not null comment '点赞数',
    status         tinyint  default 0                 not null comment '状态：0-正常',
    createTime     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    isDelete       tinyint  default 0                 not null comment '是否删除',
    INDEX idx_postId (postId),
    INDEX idx_parentId (parentId),
    INDEX idx_userId (userId)
) comment '帖子评论' collate = utf8mb4_unicode_ci;

-- 用户关注表
create table if not exists user_follow
(
    id         bigint auto_increment comment 'id' primary key,
    followerId bigint                             not null comment '关注者 id',
    followeeId bigint                             not null comment '被关注者 id',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    UNIQUE KEY uk_follower_followee (followerId, followeeId),
    INDEX idx_followerId (followerId),
    INDEX idx_followeeId (followeeId)
) comment '用户关注' collate = utf8mb4_unicode_ci;

-- 用户通知表
create table if not exists user_notification
(
    id         bigint auto_increment comment 'id' primary key,
    userId     bigint                             not null comment '接收通知的用户 id',
    fromUserId bigint                             null comment '触发通知的用户 id',
    type       varchar(32)                        not null comment '通知类型：LIKE/COMMENT/FOLLOW',
    targetId   bigint                             null comment '关联内容 id',
    summary    varchar(256)                       null comment '通知内容摘要',
    isRead     tinyint  default 0                 not null comment '是否已读',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    INDEX idx_userId (userId),
    INDEX idx_createTime (createTime)
) comment '用户通知' collate = utf8mb4_unicode_ci;
