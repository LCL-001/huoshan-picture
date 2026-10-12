-- T50：回收站 —— picture 增 deleteTime 标记"何时进回收站"
--
-- 语义：单张删除（/picture/delete）改软删 isDelete=1 + deleteTime=NOW()，进回收站；
-- 恢复 = isDelete=0 + deleteTime=NULL；保留期到点由定时任务彻底删除（见 T51）。
-- 只有 deleteTime 不为空的行才算"回收站内"——删空间时被级联软删的图片不带 deleteTime
-- （整个空间已没了，不提供空间级回收），所以回收站列表/恢复都以 deleteTime IS NOT NULL 为界，
-- 不会把级联删掉的图片误捞进个人回收站。
--
-- 存储代价：进回收站后 COS 原件+缩略图**延迟到彻底删除才清**（原先删库即清），
-- 回收站里最多多留 retention-days（默认 30 天）的存储。
--
-- 执行顺序：**先跑本脚本，再部署带该列的应用**（backend 无 Flyway，DDL 只能手工执行）；
-- 缺列会让 deletePicture 与回收站查询报 Unknown column。

ALTER TABLE picture
    ADD COLUMN deleteTime datetime NULL COMMENT '进回收站时间（NULL = 未进回收站）',
    ADD INDEX idx_deleteTime (deleteTime);
