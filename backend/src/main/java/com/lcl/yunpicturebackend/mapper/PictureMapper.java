package com.lcl.yunpicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lcl.yunpicturebackend.domain.po.Picture;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * <p>
 * 图片 Mapper 接口
 * </p>
 * <p>
 * 回收站（T50）相关的语句是**裸 SQL**：全局逻辑删除（{@code @TableLogic} + mybatis-plus 配置）会给
 * 所有 {@code BaseMapper}/Wrapper 查询自动补 {@code isDelete = 0}，读已删行只能自己写 SQL。
 * </p>
 *
 * @author author
 * @since 2026-04-18
 */
public interface PictureMapper extends BaseMapper<Picture> {

    /**
     * 回收站分页：只含 deleteTime 不为空的行（显式单张删除；级联删的图片不带 deleteTime，天然排除）。
     *
     * @param page     分页对象（必须是第一个参数，分页插件据此拦截）
     * @param all      true = 不过滤归属（站点管理员），false = 只看自己的
     * @param userId   all=false 时用于筛"公共图库里本人上传的"
     * @param spaceIds all=false 时用于筛"我有 picture:delete 的空间"；为空则只筛公共图库那部分
     */
    IPage<Picture> selectTrashPage(IPage<Picture> page,
                                   @Param("all") boolean all,
                                   @Param("userId") Long userId,
                                   @Param("spaceIds") Collection<Long> spaceIds);

    /** 取一条回收站内的记录（绕开逻辑删除过滤） */
    Picture selectTrashedById(@Param("id") Long id);

    /** 软删进回收站：isDelete=1 + deleteTime=now；返回影响行数（0 = 不存在或本来已删） */
    int softDeleteById(@Param("id") Long id);

    /** 从回收站恢复：isDelete=0 + deleteTime=NULL */
    int restoreTrashedById(@Param("id") Long id);

    /** 从回收站彻底删除（物理删行） */
    int purgeTrashedById(@Param("id") Long id);

    /** 扫过期的回收站记录（保留期清理用，见 T51）；按 deleteTime 升序，最多 limit 条 */
    List<Picture> selectExpiredTrash(@Param("deadline") Date deadline, @Param("limit") int limit);
}
