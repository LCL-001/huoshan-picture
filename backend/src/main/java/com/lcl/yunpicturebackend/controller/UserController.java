package com.lcl.yunpicturebackend.controller;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.annotation.AuthCheck;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.user.*;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.CaptchaVO;
import com.lcl.yunpicturebackend.domain.vo.LoginUserVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.utils.TextSanitizeUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p>
 * 用户 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-04-17
 */
@Api(tags = "用户相关接口")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    /**
     * 获取图形验证码
     */
    @ApiOperation("获取图形验证码")
    @GetMapping("/captcha/img")
    public BaseResponse<CaptchaVO> getCaptchaImg() {
        return ResultUtils.success(userService.createImgCaptcha());
    }

    /**
     * 发送邮箱验证码（注册/找回密码/绑定邮箱通用）
     */
    @ApiOperation("发送邮箱验证码")
    @PostMapping("/captcha/email")
    public BaseResponse<Boolean> sendEmailCaptcha(@RequestBody EmailCaptchaRequest emailCaptchaRequest,
                                                  HttpServletRequest request) {
        ThrowUtils.throwIf(emailCaptchaRequest == null, ErrorCode.PARAMS_ERROR);
        userService.sendEmailCaptcha(request, emailCaptchaRequest.getScene(), emailCaptchaRequest.getEmail(),
                emailCaptchaRequest.getCaptchaUuid(), emailCaptchaRequest.getCaptchaCode());
        return ResultUtils.success(true);
    }

    /**
     * 忘记密码：邮箱验证码重置密码
     */
    @ApiOperation("忘记密码重置")
    @PostMapping("/password/reset")
    public BaseResponse<Boolean> resetPassword(@RequestBody UserPasswordResetRequest userPasswordResetRequest) {
        ThrowUtils.throwIf(userPasswordResetRequest == null, ErrorCode.PARAMS_ERROR);
        return userService.resetPassword(userPasswordResetRequest);
    }

    /**
     * 绑定/换绑邮箱（登录态）
     */
    @ApiOperation("绑定邮箱")
    @PostMapping("/email/bind")
    public BaseResponse<Boolean> bindEmail(@RequestBody UserEmailBindRequest userEmailBindRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(userEmailBindRequest == null, ErrorCode.PARAMS_ERROR);
        return userService.bindEmail(userEmailBindRequest, request);
    }

    @ApiOperation("用户注册")
    @PostMapping("/register")
    public BaseResponse<Long> register(@RequestBody UserRegisterRequest userRegisterRequest) {
        return userService.register(userRegisterRequest);
    }

    @ApiOperation("用户登录")
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> login(@RequestBody UserLoginRequest userLoginRequest,
                                           HttpServletRequest request) {
        return userService.login(userLoginRequest, request);
    }

    @ApiOperation("获取当前登录用户")
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(BeanUtil.copyProperties(loginUser, LoginUserVO.class));
    }

    @ApiOperation("用户注销")
    @PostMapping("/logout")
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        return userService.logout(request);
    }

    /**
     * 创建用户
     */
    @ApiOperation("创建用户")
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        User user = new User();
        BeanUtils.copyProperties(userAddRequest, user);
        // 默认密码 12345678
        final String DEFAULT_PASSWORD = "12345678";
        String encryptPassword = userService.getEncryptPassword(DEFAULT_PASSWORD);
        user.setUserPassword(encryptPassword);
        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(user.getId());
    }

    /**
     * 根据 id 获取用户（仅管理员）
     */
    @ApiOperation("根据 id 获取用户")
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(user);
    }

    /**
     * 根据 id 获取包装类
     */
    @ApiOperation("根据 id 获取包装类")
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(userService.getUserVO(user));
    }

    /**
     * 删除用户
     */
    @ApiOperation("删除用户")
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean b = userService.removeById(deleteRequest.getId());
        return ResultUtils.success(b);
    }

    /**
     * 更新用户
     */
    @ApiOperation("更新用户")
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS
        userUpdateRequest.setUserName(TextSanitizeUtils.stripHtml(userUpdateRequest.getUserName()));
        userUpdateRequest.setUserProfile(TextSanitizeUtils.stripHtml(userUpdateRequest.getUserProfile()));
        User user = new User();
        BeanUtils.copyProperties(userUpdateRequest, user);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 编辑用户信息（仅本人或管理员）
     */
    @ApiOperation("编辑用户信息")
    @PostMapping("/edit")
    public BaseResponse<Boolean> editUser(@RequestBody UserEditRequest userEditRequest,
                                          HttpServletRequest request) {
        if (userEditRequest == null || userEditRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 仅本人或管理员可编辑
        User loginUser = userService.getLoginUser(request);
        ThrowUtils.throwIf(!loginUser.getId().equals(userEditRequest.getId()) && !userService.isAdmin(loginUser),
                ErrorCode.NO_AUTH_ERROR);
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS
        userEditRequest.setUserName(TextSanitizeUtils.stripHtml(userEditRequest.getUserName()));
        userEditRequest.setUserProfile(TextSanitizeUtils.stripHtml(userEditRequest.getUserProfile()));
        User user = new User();
        BeanUtils.copyProperties(userEditRequest, user);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 分页获取用户封装列表（仅管理员）
     *
     * @param userQueryRequest 查询请求参数
     */
    @ApiOperation("分页获取用户封装列表")
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = userQueryRequest.getCurrent();
        long pageSize = userQueryRequest.getPageSize();
        Page<User> userPage = userService.page(new Page<>(current, pageSize),
                userService.getQueryWrapper(userQueryRequest));
        Page<UserVO> userVOPage = new Page<>(current, pageSize, userPage.getTotal());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }

}
