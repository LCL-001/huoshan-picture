package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lcl.yunpicturebackend.domain.dto.spaceuser.SpaceUserAddRequest;
import com.lcl.yunpicturebackend.domain.dto.spaceuser.SpaceUserEditRequest;
import com.lcl.yunpicturebackend.domain.dto.spaceuser.SpaceUserQueryRequest;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.vo.SpaceUserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p>
 * 空间用户关联 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-01
 */
public interface ISpaceUserService extends IService<SpaceUser> {

    /**
     * 添加空间用户（仅空间创建者、站点管理员或团队空间管理员可调用）
     *
     * @param spaceUserAddRequest 添加请求
     * @param loginUser 登录用户（调用者）
     * @return 添加后的空间用户id
     */
    long addSpaceUser(SpaceUserAddRequest spaceUserAddRequest, User loginUser);

    /**
     * 移除空间用户（仅空间创建者、站点管理员或团队空间管理员可调用，空间属主的成员记录不可移除）
     *
     * @param id 空间用户记录id
     * @param loginUser 登录用户（调用者）
     * @return 是否移除成功
     */
    boolean deleteSpaceUser(long id, User loginUser);

    /**
     * 编辑空间用户角色（仅空间创建者、站点管理员或团队空间管理员可调用，空间属主的角色不可修改）
     *
     * @param spaceUserEditRequest 编辑请求
     * @param loginUser 登录用户（调用者）
     * @return 是否编辑成功
     */
    boolean editSpaceUser(SpaceUserEditRequest spaceUserEditRequest, User loginUser);

    /**
     * 查询某空间的成员列表（spaceId 必填，仅空间成员、属主或站点管理员可查询）
     *
     * @param spaceUserQueryRequest 查询请求
     * @param loginUser 登录用户（调用者）
     * @return 成员视图列表
     */
    List<SpaceUserVO> listSpaceUser(SpaceUserQueryRequest spaceUserQueryRequest, User loginUser);

    /**
     * 验证空间用户
     *
     * @param spaceUser 空间用户
     * @param add 是否是添加空间用户
     */
    void validSpaceUser(SpaceUser spaceUser, boolean add);

    /**
     * 获取空间用户视图
     *
     * @param spaceUser 空间用户
     * @param request 请求
     * @return 空间用户视图
     */
    SpaceUserVO getSpaceUserVO(SpaceUser spaceUser, HttpServletRequest request);

    /**
     * 获取空间用户视图列表
     *
     * @param spaceUserList 空间用户列表
     * @return 空间用户视图列表
     */
    List<SpaceUserVO> getSpaceUserVOList(List<SpaceUser> spaceUserList);

    /**
     * 获取查询条件
     *
     * @param spaceUserQueryRequest 查询请求
     * @return 查询条件
     */
    QueryWrapper<SpaceUser> getQueryWrapper(SpaceUserQueryRequest spaceUserQueryRequest);
}
