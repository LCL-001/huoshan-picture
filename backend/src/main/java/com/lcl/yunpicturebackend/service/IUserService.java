package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.domain.dto.user.UserEmailBindRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserLoginRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserPasswordResetRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserQueryRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserRegisterRequest;
import com.lcl.yunpicturebackend.domain.po.User;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.vo.CaptchaVO;
import com.lcl.yunpicturebackend.domain.vo.LoginUserVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p>
 * 用户 服务类
 * </p>
 *
 * @author author
 * @since 2026-04-17
 */
public interface IUserService extends IService<User> {

    /**
     * 用户注册
     *
     * @param userRegisterRequest
     * @return
     */
    BaseResponse<Long> register(UserRegisterRequest userRegisterRequest);

    /**
     * 用户登录
     *
     * @param userLoginRequest
     * @param request
     * @return
     */
    BaseResponse<LoginUserVO> login(UserLoginRequest userLoginRequest, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    BaseResponse<Boolean> logout(HttpServletRequest request);

    /**
     * 生成图形验证码
     *
     * @return 验证码凭证与图片（base64）
     */
    CaptchaVO createImgCaptcha();

    /**
     * 校验图形验证码（一次性，无论对错立即作废，防重放）
     *
     * @param captchaUuid 验证码凭证
     * @param captchaCode 用户输入的验证码
     */
    void verifyImgCaptcha(String captchaUuid, String captchaCode);

    /**
     * 发送邮箱验证码（图形验证码前置 + 多维频控；按场景静默跳过防枚举：
     * register 跳过已注册邮箱，reset 跳过未注册邮箱，bind 跳过任何已注册邮箱）
     *
     * @param request      请求（用于取客户端 IP 频控）
     * @param scene        使用场景：register / reset / bind
     * @param email        目标邮箱
     * @param captchaUuid  图形验证码凭证
     * @param captchaCode  图形验证码
     */
    void sendEmailCaptcha(HttpServletRequest request, String scene, String email, String captchaUuid, String captchaCode);

    /**
     * 校验邮箱验证码（一次性；同码错误 5 次作废）
     *
     * @param email     邮箱
     * @param emailCode 邮箱验证码
     */
    void verifyEmailCaptcha(String email, String emailCode);

    /**
     * 忘记密码：邮箱验证码校验通过后重置密码
     *
     * @param userPasswordResetRequest 邮箱 + 验证码 + 新密码
     * @return 是否重置成功
     */
    BaseResponse<Boolean> resetPassword(UserPasswordResetRequest userPasswordResetRequest);

    /**
     * 绑定/换绑当前登录用户的邮箱（需邮箱验证码）
     *
     * @param userEmailBindRequest 邮箱 + 验证码
     * @param request              请求
     * @return 是否绑定成功
     */
    BaseResponse<Boolean> bindEmail(UserEmailBindRequest userEmailBindRequest, HttpServletRequest request);

    /**
     * 获取查询包装类
     *
     * @param userQueryRequest
     * @return
     */
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);

    /**
     * 获取用户视图
     *
     * @param user
     * @return
     */
    UserVO getUserVO(User user);

    /**
     * 获取用户视图列表
     *
     * @param userList
     * @return
     */
    List<UserVO> getUserVOList(List<User> userList);

    /**
     * 获取加密密码
     * @param defaultPassword
     * @return
     */
    String getEncryptPassword(String defaultPassword);

    /**
     * 是否为管理员
     *
     * @param user
     * @return
     */
    boolean isAdmin(User user);

}
