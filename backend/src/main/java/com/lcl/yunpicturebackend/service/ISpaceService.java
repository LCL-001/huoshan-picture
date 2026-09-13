package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceAddRequest;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceEditRequest;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceQueryRequest;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceUpdateRequest;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.SpaceVO;

import javax.servlet.http.HttpServletRequest;

/**
 * <p>
 * 空间 服务类
 * </p>
 *
 * @author author
 * @since 2026-04-24
 */
public interface ISpaceService extends IService<Space> {

    /**
     * 添加空间
     *
     * @param spaceAddRequest 添加请求
     * @param request 请求
     * @return 添加后的空间id
     */
    Long addSpace(SpaceAddRequest spaceAddRequest, HttpServletRequest request);

    /**
     * 获取查询条件
     *
     * @param spaceQueryRequest 查询条件
     * @return 查询条件
     */
    QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest);

    /**
     * 获取空间封装类
     *
     * @param space 空间
     * @param request 请求
     * @return 空间封装类
     */
    SpaceVO getSpaceVO(Space space, HttpServletRequest request);

    /**
     * 获取空间分页封装类
     *
     * @param spacePage 空间分页
     * @param request 请求
     * @return 空间分页封装类
     */
    Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request);

    /**
     * 验证空间
     *
     * @param space 空间
     */
    void validSpace(Space space, boolean add);


    /**
     * 根据空间级别填充空间限额
     *
     * @param space 空间
     */
    void fillSpaceBySpaceLevel(Space space);

    /**
     * 删除空间
     *
     * @param deleteRequest 删除请求
     * @param request 请求
     */
    void deleteSpace(DeleteRequest deleteRequest, HttpServletRequest request);

    /**
     * 删除用户的级联清理：名下空间逐个走空间级联（逻辑删空间行、成员记录与图片，
     * 事务提交后清理 COS 文件），并移除其在他人团队空间的成员关系。
     * 供删号流程调用；账号行删除与会话踢除由调用方负责。
     *
     * @param userId 被删用户 id
     */
    void deleteUserCascade(long userId);

    /**
     * 更新空间
     *
     * @param spaceUpdateRequest 更新请求
     * @param request 请求
     */
    void updateSpace(SpaceUpdateRequest spaceUpdateRequest, HttpServletRequest request);

    /**
     * 编辑空间
     *
     * @param spaceEditRequest 编辑请求
     * @param request 请求
     */
    void editSpace(SpaceEditRequest spaceEditRequest, HttpServletRequest request);

    /**
     * 检查空间权限
     *
     * @param oldSpace 旧空间
     * @param loginUser 登录用户
     */
    void checkSpaceAuth(Space oldSpace, User loginUser);

    /**
     * 分页查询与用户相关的空间（本人空间 + 已加入的团队空间），
     * 请求中的筛选条件在此可见范围内继续生效。供非管理员的空间列表使用。
     *
     * @param loginUser         登录用户
     * @param spaceQueryRequest 查询条件
     * @param current           页码
     * @param size              页大小
     * @return 空间分页
     */
    Page<Space> listMyRelatedSpaceByPage(User loginUser, SpaceQueryRequest spaceQueryRequest,
                                         long current, long size);
}
