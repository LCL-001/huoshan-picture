package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lcl.yunpicturebackend.domain.dto.spaceuser.SpaceUserAddRequest;
import com.lcl.yunpicturebackend.domain.dto.spaceuser.SpaceUserEditRequest;
import com.lcl.yunpicturebackend.domain.dto.spaceuser.SpaceUserQueryRequest;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.SpaceUserVO;
import com.lcl.yunpicturebackend.domain.vo.SpaceVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.enums.SpaceRoleEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.mapper.SpaceUserMapper;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 空间用户关联 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
public class SpaceUserServiceImpl extends ServiceImpl<SpaceUserMapper, SpaceUser> implements ISpaceUserService {

    private final IUserService userService;
    @Resource
    @Lazy
    private ISpaceService spaceService;

    /**
     * 添加空间用户（仅空间创建者、站点管理员或团队空间管理员可调用）
     * @param spaceUserAddRequest 添加请求
     * @param loginUser 登录用户（调用者）
     * @return 添加后的空间用户id
     */
    @Override
    public long addSpaceUser(SpaceUserAddRequest spaceUserAddRequest, User loginUser) {
        // 参数校验
        ThrowUtils.throwIf(spaceUserAddRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        SpaceUser spaceUser = new SpaceUser();
        BeanUtils.copyProperties(spaceUserAddRequest, spaceUser);
        validSpaceUser(spaceUser, true);
        // 调用者身份校验：以数据库中的空间归属与成员角色为准，绝不信任请求参数
        Space space = spaceService.getById(spaceUser.getSpaceId());
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        spaceService.checkSpaceAuth(space, loginUser);
        // 数据库操作
        boolean result = this.save(spaceUser);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return spaceUser.getId();
    }

    /**
     * 移除空间用户（仅空间创建者、站点管理员或团队空间管理员可调用）
     * @param id 空间用户记录id
     * @param loginUser 登录用户（调用者）
     * @return 是否移除成功
     */
    @Override
    public boolean deleteSpaceUser(long id, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        SpaceUser oldSpaceUser = this.getById(id);
        ThrowUtils.throwIf(oldSpaceUser == null, ErrorCode.NOT_FOUND_ERROR);
        Space space = spaceService.getById(oldSpaceUser.getSpaceId());
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        spaceService.checkSpaceAuth(space, loginUser);
        // 空间属主的成员记录不可移除
        ThrowUtils.throwIf(space.getUserId().equals(oldSpaceUser.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "不能移除空间属主");
        return this.removeById(id);
    }

    /**
     * 编辑空间用户角色（仅空间创建者、站点管理员或团队空间管理员可调用）
     * @param spaceUserEditRequest 编辑请求
     * @param loginUser 登录用户（调用者）
     * @return 是否编辑成功
     */
    @Override
    public boolean editSpaceUser(SpaceUserEditRequest spaceUserEditRequest, User loginUser) {
        ThrowUtils.throwIf(spaceUserEditRequest == null || spaceUserEditRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        SpaceUser oldSpaceUser = this.getById(spaceUserEditRequest.getId());
        ThrowUtils.throwIf(oldSpaceUser == null, ErrorCode.NOT_FOUND_ERROR);
        Space space = spaceService.getById(oldSpaceUser.getSpaceId());
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        spaceService.checkSpaceAuth(space, loginUser);
        // 空间属主的角色不可修改
        ThrowUtils.throwIf(space.getUserId().equals(oldSpaceUser.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "不能修改空间属主的角色");
        SpaceUser spaceUser = new SpaceUser();
        BeanUtils.copyProperties(spaceUserEditRequest, spaceUser);
        // 空间归属以目标记录为准，防止跨空间篡改
        spaceUser.setSpaceId(oldSpaceUser.getSpaceId());
        validSpaceUser(spaceUser, false);
        return this.updateById(spaceUser);
    }

    /**
     * 查询某空间的成员列表（spaceId 必填，仅空间成员、属主或站点管理员可查询）
     * @param spaceUserQueryRequest 查询请求
     * @param loginUser 登录用户（调用者）
     * @return 成员视图列表
     */
    @Override
    public List<SpaceUserVO> listSpaceUser(SpaceUserQueryRequest spaceUserQueryRequest, User loginUser) {
        ThrowUtils.throwIf(spaceUserQueryRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // spaceId 强制非空，防止查询条件失效导致全表泄露
        Long spaceId = spaceUserQueryRequest.getSpaceId();
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR, "空间 id 不能为空");
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 调用者必须是该空间的成员、属主或站点管理员
        boolean isMember = this.lambdaQuery()
                .eq(SpaceUser::getSpaceId, spaceId)
                .eq(SpaceUser::getUserId, loginUser.getId())
                .exists();
        ThrowUtils.throwIf(!isMember && !space.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser),
                ErrorCode.NO_AUTH_ERROR, "没有空间权限");
        List<SpaceUser> spaceUserList = this.list(this.getQueryWrapper(spaceUserQueryRequest));
        return this.getSpaceUserVOList(spaceUserList);
    }

    /**
     * 验证空间用户
     * @param spaceUser 空间用户
     * @param add 是否是添加空间用户
     */
    @Override
    public void validSpaceUser(SpaceUser spaceUser, boolean add) {
        ThrowUtils.throwIf(spaceUser == null, ErrorCode.PARAMS_ERROR);
        // 创建时，空间 id 和用户 id 必填
        Long spaceId = spaceUser.getSpaceId();
        Long userId = spaceUser.getUserId();
        if (add) {
            ThrowUtils.throwIf(ObjectUtil.hasEmpty(spaceId, userId), ErrorCode.PARAMS_ERROR);
            User user = userService.getById(userId);
            ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            SpaceUser exist = lambdaQuery()
                    .eq(SpaceUser::getSpaceId, spaceId)
                    .eq(SpaceUser::getUserId, userId)
                    .one();
            ThrowUtils.throwIf(exist != null, ErrorCode.OPERATION_ERROR, "空间用户已存在");
        }
        // 校验空间角色
        String spaceRole = spaceUser.getSpaceRole();
        SpaceRoleEnum spaceRoleEnum = SpaceRoleEnum.getEnumByValue(spaceRole);
        if (spaceRole != null && spaceRoleEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间角色不存在");
        }
    }

    /**
     * 获取空间用户视图
     * @param spaceUser 空间用户
     * @param request 请求
     * @return 空间用户视图
     */
    @Override
    public SpaceUserVO getSpaceUserVO(SpaceUser spaceUser, HttpServletRequest request) {
        // 对象转封装类
        SpaceUserVO spaceUserVO = SpaceUserVO.objToVo(spaceUser);
        // 关联查询用户信息
        Long userId = spaceUser.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            spaceUserVO.setUser(userVO);
        }
        // 关联查询空间信息
        Long spaceId = spaceUser.getSpaceId();
        if (spaceId != null && spaceId > 0) {
            Space space = spaceService.getById(spaceId);
            SpaceVO spaceVO = spaceService.getSpaceVO(space, request);
            spaceUserVO.setSpace(spaceVO);
        }
        return spaceUserVO;
    }

    /**
     * 获取空间用户视图列表
     * @param spaceUserList
     * @return
     */
    @Override
    public List<SpaceUserVO> getSpaceUserVOList(List<SpaceUser> spaceUserList) {
        // 判断输入列表是否为空
        if (CollUtil.isEmpty(spaceUserList)) {
            return Collections.emptyList();
        }
        // 对象列表 => 封装对象列表
        List<SpaceUserVO> spaceUserVOList = spaceUserList.stream().map(SpaceUserVO::objToVo).collect(Collectors.toList());
        // 1. 收集需要关联查询的用户 ID 和空间 ID
        Set<Long> userIdSet = spaceUserList.stream().map(SpaceUser::getUserId).collect(Collectors.toSet());
        Set<Long> spaceIdSet = spaceUserList.stream().map(SpaceUser::getSpaceId).collect(Collectors.toSet());
        // 2. 批量查询用户和空间
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        Map<Long, List<Space>> spaceIdSpaceListMap = spaceService.listByIds(spaceIdSet).stream()
                .collect(Collectors.groupingBy(Space::getId));
        // 3. 填充 SpaceUserVO 的用户和空间信息
        spaceUserVOList.forEach(spaceUserVO -> {
            Long userId = spaceUserVO.getUserId();
            Long spaceId = spaceUserVO.getSpaceId();
            // 填充用户信息
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            spaceUserVO.setUser(userService.getUserVO(user));
            // 填充空间信息
            Space space = null;
            if (spaceIdSpaceListMap.containsKey(spaceId)) {
                space = spaceIdSpaceListMap.get(spaceId).get(0);
            }
            spaceUserVO.setSpace(SpaceVO.objToVo(space));
        });
        // 过滤已删除空间的记录
        spaceUserVOList.removeIf(vo -> vo.getSpace() == null);
        return spaceUserVOList;
    }


    /**
     * 获取查询条件
     * @param spaceUserQueryRequest 查询请求
     * @return 查询条件
     */
    @Override
    public QueryWrapper<SpaceUser> getQueryWrapper(SpaceUserQueryRequest spaceUserQueryRequest) {
        QueryWrapper<SpaceUser> queryWrapper = new QueryWrapper<>();
        if (spaceUserQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = spaceUserQueryRequest.getId();
        Long spaceId = spaceUserQueryRequest.getSpaceId();
        Long userId = spaceUserQueryRequest.getUserId();
        String spaceRole = spaceUserQueryRequest.getSpaceRole();
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceRole), "spaceRole", spaceRole);
        return queryWrapper;
    }

}
