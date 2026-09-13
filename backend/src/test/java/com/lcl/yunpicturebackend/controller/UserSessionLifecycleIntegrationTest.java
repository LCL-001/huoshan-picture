package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.user.UserLoginRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserPasswordResetRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserUpdateRequest;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.service.IUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.TimeUnit;

import cn.hutool.core.lang.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话生命周期回归（docs/plan.md T3.3）：
 * 改密 / 删号 / 降权后必须踢掉该用户的 Sa-Token 会话——否则会话里的用户快照
 * （尤其 admin 角色）最长 7 天内仍会参与空间鉴权；getLoginUser 对匿名请求不得创建会话。
 * <p>
 * 登录走真实 login 流程（种子图形验证码绕过图形校验，频控保持开启）；
 * 邮箱验证码直接种子 Redis（键格式 huoshantuku:auth:captcha:email:{email}）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserSessionLifecycleIntegrationTest {

    /** 与 UserServiceImpl 中的常量保持一致（私有常量，测试内复制键前缀） */
    private static final String IMG_CAPTCHA_KEY = "huoshantuku:auth:captcha:img:";
    private static final String EMAIL_CAPTCHA_KEY = "huoshantuku:auth:captcha:email:";
    private static final String RAW_PASSWORD = "password123";

    @Autowired
    private IUserService userService;
    @Autowired
    private UserController userController;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private User admin;
    private User resetTarget;
    private User deleteTarget;
    private User downgradeTarget;

    @BeforeAll
    void setUpFixtures() {
        admin = newUser("admin", UserConstant.ADMIN_ROLE, null);
        resetTarget = newUser("resetpw", UserConstant.DEFAULT_ROLE, "reset-" + UUID.randomUUID() + "@test.com");
        deleteTarget = newUser("deluser", UserConstant.DEFAULT_ROLE, null);
        downgradeTarget = newUser("downgrade", UserConstant.ADMIN_ROLE, null);
    }

    @AfterAll
    void cleanFixtures() {
        List.of(admin, resetTarget, deleteTarget, downgradeTarget).forEach(u -> {
            if (u != null) {
                userService.removeById(u.getId());
            }
        });
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ===== 验收主用例：resetPassword 后旧 satoken 失效 =====

    @Test
    void resetPasswordShouldRevokeSaTokenSession() {
        login(resetTarget, RAW_PASSWORD);
        assertNotNull(StpKit.SPACE.getSessionByLoginId(resetTarget.getId(), false), "前置：登录后应有 Sa-Token 会话");
        stringRedisTemplate.opsForValue().set(EMAIL_CAPTCHA_KEY + resetTarget.getEmail(), "123456",
                5, TimeUnit.MINUTES);
        UserPasswordResetRequest resetRequest = new UserPasswordResetRequest();
        resetRequest.setEmail(resetTarget.getEmail());
        resetRequest.setEmailCode("123456");
        resetRequest.setNewPassword("newpass4567");
        BaseResponse<Boolean> response = userService.resetPassword(resetRequest);
        assertTrue(response.getData());
        assertNull(StpKit.SPACE.getSessionByLoginId(resetTarget.getId(), false),
                "改密后旧 Sa-Token 会话必须被踢掉");
        // 旧密码登录失败、新密码登录成功，确认密码确已更换
        UserLoginRequest oldPassword = buildLoginRequest(resetTarget, RAW_PASSWORD);
        assertThrows(BusinessException.class, () -> userService.login(oldPassword, currentRequest()));
        login(resetTarget, "newpass4567");
        assertNotNull(StpKit.SPACE.getSessionByLoginId(resetTarget.getId(), false));
    }

    // ===== 删号踢会话 =====

    @Test
    void deleteUserShouldRevokeSaTokenSession() {
        login(deleteTarget, RAW_PASSWORD);
        assertNotNull(StpKit.SPACE.getSessionByLoginId(deleteTarget.getId(), false));
        bindRequest(admin);
        DeleteRequest deleteRequest = new DeleteRequest();
        deleteRequest.setId(deleteTarget.getId());
        BaseResponse<Boolean> response = userController.deleteUser(deleteRequest);
        assertTrue(response.getData());
        assertNull(StpKit.SPACE.getSessionByLoginId(deleteTarget.getId(), false), "删号后 Sa-Token 会话必须被踢掉");
        assertNull(userService.getById(deleteTarget.getId()));
    }

    // ===== 降权踢会话 =====

    @Test
    void roleDowngradeShouldRevokeSaTokenSession() {
        login(downgradeTarget, RAW_PASSWORD);
        assertNotNull(StpKit.SPACE.getSessionByLoginId(downgradeTarget.getId(), false));
        bindRequest(admin);
        UserUpdateRequest updateRequest = new UserUpdateRequest();
        updateRequest.setId(downgradeTarget.getId());
        updateRequest.setUserRole(UserConstant.DEFAULT_ROLE);
        BaseResponse<Boolean> response = userController.updateUser(updateRequest);
        assertTrue(response.getData());
        assertNull(StpKit.SPACE.getSessionByLoginId(downgradeTarget.getId(), false),
                "降权后旧会话快照（admin 角色）必须立即失效");
        assertEquals(UserConstant.DEFAULT_ROLE, userService.getById(downgradeTarget.getId()).getUserRole());
    }

    // ===== 匿名请求不建会话 =====

    @Test
    void anonymousGetLoginUserShouldNotCreateSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getLoginUser(request));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
        assertNull(request.getSession(false), "匿名请求不应创建会话");
    }

    // ===== 夹具与工具 =====

    private User newUser(String name, String role, String email) {
        User user = new User();
        user.setUserAccount(name + "-" + UUID.randomUUID());
        user.setUserPassword(userService.getEncryptPassword(RAW_PASSWORD));
        user.setUserName(name);
        user.setUserRole(role);
        user.setEmail(email);
        userService.save(user);
        return user;
    }

    private UserLoginRequest buildLoginRequest(User user, String rawPassword) {
        String uuid = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForValue().set(IMG_CAPTCHA_KEY + uuid, "abcd", 2, TimeUnit.MINUTES);
        UserLoginRequest loginRequest = new UserLoginRequest();
        loginRequest.setUserAccount(user.getUserAccount());
        loginRequest.setUserPassword(rawPassword);
        loginRequest.setCaptchaUuid(uuid);
        loginRequest.setCaptchaCode("abcd");
        return loginRequest;
    }

    private void login(User user, String rawPassword) {
        bindRequest(user);
        userService.login(buildLoginRequest(user, rawPassword), currentRequest());
    }

    private void bindRequest(User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        if (user != null) {
            request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, user);
        }
    }

    private MockHttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return (MockHttpServletRequest) attributes.getRequest();
    }
}
