-- 邮箱注册基础：user 表新增 email 列 + 唯一索引
-- 本地与生产库均需执行；唯一索引允许多个 NULL，存量用户不受影响
use yu_picture;
ALTER TABLE `user` ADD COLUMN `email` varchar(128) NULL DEFAULT NULL COMMENT '邮箱' AFTER `userAccount`;
ALTER TABLE `user` ADD UNIQUE INDEX `uk_user_email` (`email`);
