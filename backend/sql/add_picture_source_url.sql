-- T29：URL 导入按"源地址 + 目标空间"判重
--
-- 背景：原来判重拿**源地址**去比对 picture.url，而库里存的是 COS 地址（host + 随机 key），
-- 两者不同名空间，"图片已存在"这条检查对 URL 导入永远命中不了。改用独立的 sourceUrl 列记录来源。
--
-- 语义：URL 导入（/picture/upload/url）与批量抓取写入来源地址；MultipartFile 上传写 NULL。
-- 同一源地址在同一空间内只导一次；跨空间、公共图库与空间之间互不影响（判重带空间条件）；
-- 逻辑删除的记录不参与判重（MyBatis-Plus @TableLogic 自动过滤），删掉后可重新导入。
--
-- 索引：utf8mb4 下 varchar(1024) 的全列索引是 4096 字节，超过 InnoDB 3072 字节上限，
-- 因此用前缀索引（等值查询仍能走索引，回表时再核对完整值）。刻意不建唯一索引：
-- 逻辑删除的行会永久占住唯一索引（tag/user 已踩过这个坑），判重靠查询而不是约束。
--
-- 执行顺序：**先跑本脚本，再部署带该列的应用**。backend 没有 Flyway，DDL 只能手工执行；
-- 文件上传不写该列（不受影响），但 URL 导入与批量抓取的判重查询需要它，缺列会报 Unknown column。

ALTER TABLE picture
    ADD COLUMN sourceUrl varchar(1024) NULL COMMENT '来源地址（URL 导入/批量抓取写入，按空间判重用）' AFTER tags,
    ADD INDEX idx_sourceUrl (sourceUrl(191));
