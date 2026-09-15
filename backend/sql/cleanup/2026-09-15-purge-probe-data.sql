-- =============================================================================
-- 清理历史探针数据（2026-09-15，用户要求）
--
-- ⚠ 本脚本**已于 2026-09-15 在生产库上执行完毕**，此处入库是为了留痕与可复跑
--   （规则 16：证据不能只留在 %TEMP%）。重复执行是安全的（目标行已不存在）。
--
-- 删了什么（执行前后实测）：
--   活跃用户    38 → 8     （删 30 个探针账号）
--   活跃空间    37 → 6     （删 14 个测试空间 + 17 个"属主已删"的无主空间）
--   活跃图片   178 → 156   （删 21 张测试图 + 1 张父级空间已不存在的图）
--   活跃词条    26 → 17    （删 9 个 T5 集成测试词）
--   悬空成员行   2 → 0     （指向根本不存在的空间；属主是 1234，本身已是死行）
--
-- 保留了什么（**用户自己在用的账号，一行都没动**）：
--   1234 / 12345 / admin / 123456 / 66666 / 5555 / test123 / test111
--   其中 1234 有 2 空间 144 图、admin 有 2 空间 10 图、12345 有 2 空间 2 图
--   词表保留 5 分类 + 9 种子标签 + 风景/雪山/自然（后三个是 T24 打标验收真实创建的，不是测试残留）
--
-- ⚠ 两个执行中发现并纠正的坑（写下来免得下次再踩）：
--   ① MySQL 的 LIKE **没有 `[...]` 字符类**。写 `userAccount like 'sec[_]%'` 会匹配 **0 条**、
--      把 15 个账号静默漏掉（dry-run 才发现的）。而 `'sec_%'` 里的 `_` 是通配符，会连 `secret…`
--      一起匹配。**精确前缀一律用 `left(userAccount, N) = '...'`**，不用 LIKE。
--   ② 一开始把 `1234` / `admin` 也当成"历史探针"，盘点后发现它们分别有 144 / 10 张图、
--      是管理员账号——**删之前必须先看属主的真实足迹**，不能照名字猜。
--
-- ⚠ 副作用：图片是**物理删除**，其 COS 对象不会被回收（应用内的删除会异步清 COS，这里绕过了它）。
--   删掉的是测试图，代价可接受；若要回收 COS 对象需另行处理。
--
-- 本次**未处理**：逻辑删除（isDelete=1）的历史残留——377 用户 / 206 空间 / 362 图片。
--   它们对应用不可见（MyBatis-Plus 逻辑删除自动过滤），但占着库、且唯一索引仍被占用
--   （同名词条/账号不可再注册）。要清需另开任务，见 docs/plans/records/probe-data-purge-2026-09-15.md。
-- =============================================================================

-- ---------- 第一步：探针账号与其测试内容 ----------

-- 1) 成员行：两个方向都清
delete from space_user where userId in (
    select id from user
    where left(userAccount, 4) = 'sec_' or userAccount = 'zcodetest'
       or userAccount like 'tag-vocab-%' or userAccount like 'tier1probe%' or userAccount like 't12probe%');

delete from space_user where spaceId in (
    select id from space where userId in (
        select id from user
        where userAccount like 'tag-vocab-%' or userAccount like 'tier1probe%' or userAccount like 't12probe%'));

-- 2) 测试内容：图片 -> 空间
delete from picture where userId in (
    select id from user
    where userAccount like 'tag-vocab-%' or userAccount like 'tier1probe%' or userAccount like 't12probe%');

delete from space where userId in (
    select id from user
    where userAccount like 'tag-vocab-%' or userAccount like 'tier1probe%' or userAccount like 't12probe%');

-- 3) 探针账号本身
delete from user
where left(userAccount, 4) = 'sec_'
   or userAccount = 'zcodetest'
   or userAccount like 'tag-vocab-%'
   or userAccount like 'tier1probe%'
   or userAccount like 't12probe%';

-- 4) 词表：只删 T5 集成测试词（`风景/雪山/自然` 是打标验收真实创建的，保留）
delete from tag where name like 'T5新词%';

-- ---------- 第二步：清掉"活着但父级已不存在"的可见残留 ----------
-- 来源：空间授权集成测试（T3.1/T3.2）建 `outsider-<uuid>` 用户 + `outsider-team-<uuid>` 空间，
--       收尾只逻辑删了用户、没删空间 ⇒ 空间仍 isDelete=0，在管理端空间列表里显示为无属主垃圾。

delete from space_user where spaceId in (
    select id from space where isDelete = 0
      and userId not in (select id from user where isDelete = 0));

delete from space where isDelete = 0
  and userId not in (select id from user where isDelete = 0);

delete from picture where isDelete = 0 and spaceId is not null
  and spaceId not in (select id from space where isDelete = 0);

-- ---------- 第三步：悬空成员行 ----------
delete from space_user where spaceId not in (select id from space);

-- ---------- 附带（不在 SQL 里，用 redis-cli 做）----------
-- Redis db1 里清掉 11 个 satoken 键，它们的属主是被删的探针账号（键名 satoken:space:session:<id>
-- 或值等于该 id 的 token 键）。判据：id 不在 user 表里。303 -> 292 键。
