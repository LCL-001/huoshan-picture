package com.lcl.yunpicturebackend.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.annotation.AuthCheck;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.space.*;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.SpaceVO;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.manager.auth.SpaceUserAuthManager;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p>
 * 空间 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-04-24
 */
@RestController
@RequestMapping("/space")
@RequiredArgsConstructor
@Api(tags = "空间相关接口")
public class SpaceController {
    private final ISpaceService spaceService;
    private final IUserService userService;
    private final SpaceUserAuthManager spaceUserAuthManager;

    /**
     * 创建空间
     */
    @ApiOperation("创建空间")
    @PostMapping("/add")
    public BaseResponse<Long> addSpace(@RequestBody SpaceAddRequest spaceAddRequest, HttpServletRequest request) {
        Long spaceId = spaceService.addSpace(spaceAddRequest, request);
        return ResultUtils.success(spaceId);
    }

    /**
     * 删除空间
     */
    @ApiOperation("删除空间")
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteSpace(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        spaceService.deleteSpace(deleteRequest, request);
        return ResultUtils.success(true);
    }

    /**
     * 更新空间（仅管理员可用）
     */
    @ApiOperation("更新空间")
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateSpace(@RequestBody SpaceUpdateRequest spaceUpdateRequest,
                                               HttpServletRequest request) {
        spaceService.updateSpace(spaceUpdateRequest, request);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取空间（仅管理员可用）
     */
    @ApiOperation("根据 id 获取空间")
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Space> getSpaceById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Space space = spaceService.getById(id);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(space);
    }

    /**
     * 根据 id 获取空间（封装类）
     */
    @ApiOperation("根据 id 获取空间（封装类）")
    @GetMapping("/get/vo")
    public BaseResponse<SpaceVO> getSpaceVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Space space = spaceService.getById(id);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR);

        SpaceVO spaceVO = spaceService.getSpaceVO(space, request);
        User loginUser = userService.getLoginUser(request);
        List<String> permissionList = spaceUserAuthManager.getPermissionList(space, loginUser);
        spaceVO.setPermissionList(permissionList);
        // 获取封装类
        return ResultUtils.success(spaceVO);
    }

    /**
     * 分页获取空间列表（仅管理员可用）
     */
    @ApiOperation("分页获取空间列表")
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Space>> listSpaceByPage(@RequestBody SpaceQueryRequest spaceQueryRequest) {
        long current = spaceQueryRequest.getCurrent();
        long size = spaceQueryRequest.getPageSize();
        // 查询数据库
        Page<Space> spacePage = spaceService.page(new Page<>(current, size),
                spaceService.getQueryWrapper(spaceQueryRequest));
        return ResultUtils.success(spacePage);
    }

    /**
     * 分页获取空间列表（封装类）
     */
    @ApiOperation("分页获取空间列表（封装类）")
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<SpaceVO>> listSpaceVOByPage(@RequestBody SpaceQueryRequest spaceQueryRequest,
                                                             HttpServletRequest request) {
        long current = spaceQueryRequest.getCurrent();
        long size = spaceQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 100, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Page<Space> spacePage = spaceService.page(new Page<>(current, size),
                spaceService.getQueryWrapper(spaceQueryRequest));
        // 获取封装类
        return ResultUtils.success(spaceService.getSpaceVOPage(spacePage, request));
    }

    /**
     * 编辑空间（给用户使用）
     */
    @ApiOperation("编辑空间")
    @PostMapping("/edit")
    public BaseResponse<Boolean> editSpace(@RequestBody SpaceEditRequest spaceEditRequest, HttpServletRequest request) {
        spaceService.editSpace(spaceEditRequest, request);
        return ResultUtils.success(true);
    }

}