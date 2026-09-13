-- 标签词表（docs/plan.md T5，全局词表不按空间拆）
use yu_picture;

SET NAMES utf8mb4;

create table if not exists tag
(
    id         bigint auto_increment comment 'id' primary key,
    name       varchar(64)                            not null comment '词条名称',
    type       varchar(16)  default 'tag'             not null comment '词条类型：tag-标签 category-分类',
    usageCount bigint       default 0                 not null comment '使用次数',
    createTime datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint      default 0                 not null comment '是否删除',
    -- 词条在同类型下唯一（并发首插兜底 + 种子幂等的前提）
    UNIQUE KEY uk_name_type (name, type),
    INDEX idx_type (type)
) comment '标签词表' collate = utf8mb4_unicode_ci;

-- 种子迁移：原 PictureController.listPictureTagCategory 硬编码的 9 标签 + 5 分类（INSERT IGNORE 幂等，可重复执行）
insert ignore into tag (name, type) values
('热门', 'tag'), ('搞笑', 'tag'), ('生活', 'tag'), ('高清', 'tag'), ('艺术', 'tag'),
('校园', 'tag'), ('背景', 'tag'), ('简历', 'tag'), ('创意', 'tag'),
('模板', 'category'), ('电商', 'category'), ('表情包', 'category'), ('素材', 'category'), ('海报', 'category');
