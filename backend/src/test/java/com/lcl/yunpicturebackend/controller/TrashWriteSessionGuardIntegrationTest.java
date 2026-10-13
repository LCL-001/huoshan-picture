package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureTrashBatchRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserLoginRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserPasswordResetRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserUpdateRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashResultVO;
import com.lcl.yunpicturebackend.enums.SpaceLevelEnum;
import com.lcl.yunpicturebackend.enums.SpaceTypeEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.mapper.PictureMapper;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
import com.lcl.yunpicturebackend.service.IUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收站写操作的有效会话校验回归（docs/plan.md T56）。
 * <p>
 * 删除 / 恢复 / 彻底删除同时存在两套登录凭据：Spring Session（HttpSession 的 USER_LOGIN_STATE）与
 * Sa-Token 的 space 会话（StpKit.SPACE）。两者独立：可能缺失（改密 / 登出后 Sa-Token 会话已被撤销，
 * HttpSession 仍在）、也可能各自指向不同的人。写操作必须在两套凭据都存在且同属一人后才做逐资源 RBAC，
 * 否则注解（@SaSpaceCheckPermission 按 satoken 判定）与实际执行（按 Spring Session 判定）可能不是同一个人。
 * <p>
 * 直接调 service 层（绕开 MVC 注解拦截），单独钉住服务层的这道校验；DB 走真实 MySQL。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("回收站写操作会话校验（真实库）")
class TrashWriteSessionGuardIntegrationTest {

    private static final String IMG_CAPTCHA_KEY = "huoshantuku:auth:captcha:img:";
    private static final String EMAIL_CAPTCHA_KEY = "huoshantuku:auth:captcha:email:";
    private static final String RAW_PASSWORD = "password123";
    private static final String SATOKEN_COOKIE = "satoken";

    @Autowired
    private IPictureService pictureService;
    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private ISpaceUserService spaceUserService;
    @Autowired
    private IUserService userService;
    @Autowired
    private UserController userController;
    @Autowired
    private PictureMapper pictureMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User admin;
    private User owner;
    private User other;
    private User member;
    private User teamViewer;
    private User resetTarget;
    private User downgradeTarget;
    private Space teamSpace;
    private Space privateSpace;
    private final List<Long> createdPictureIds = new ArrayList<>();

    @BeforeAll
    void setUpFixtures() {
        admin = newUser("trash-guard-admin", UserConstant.ADMIN_ROLE, null);
        owner = newUser("trash-guard-owner", UserConstant.DEFAULT_ROLE, null);
        other = newUser("trash-guard-other", UserConstant.DEFAULT_ROLE, null);
        member = newUser("trash-guard-member", UserConstant.DEFAULT_ROLE, null);
        teamViewer = newUser("trash-guard-viewer", UserConstant.DEFAULT_ROLE, null);
        resetTarget = newUser("trash-guard-reset", UserConstant.DEFAULT_ROLE,
                "trash-guard-reset-" + UUID.randomUUID() + "@test.com");
        downgradeTarget = newUser("trash-guard-downgrade", UserConstant.ADMIN_ROLE, null);

        privateSpace = newSpace("trash-guard-private", SpaceTypeEnum.PRIVATE.getValue(), owner.getId());
        teamSpace = newSpace("trash-guard-team", SpaceTypeEnum.TEAM.getValue(), owner.getId());
        // editor 才有 picture:delete；viewer 没有（用于权限正/负例）
        spaceUserService.save(new SpaceUser().setSpaceId(teamSpace.getId())
                .setUserId(member.getId()).setSpaceRole("editor"));
        spaceUserService.save(new SpaceUser().setSpaceId(teamSpace.getId())
                .setUserId(teamViewer.getId()).setSpaceRole("viewer"));
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterAll
    void cleanFixtures() {
        // 部分图片被置为回收站（isDelete=1），逻辑删拦不住，统一裸 SQL 物理清
        createdPictureIds.forEach(id -> jdbcTemplate.update("DELETE FROM picture WHERE id = ?", id));
        List.of(privateSpace, teamSpace).forEach(space -> {
            if (space != null) {
                jdbcTemplate.update("DELETE FROM space_user WHERE spaceId = ?", space.getId());
                jdbcTemplate.update("DELETE FROM space WHERE id = ?", space.getId());
            }
        });
        List.of(admin, owner, other, member, teamViewer, resetTarget, downgradeTarget).forEach(u -> {
            if (u != null) {
                userService.removeById(u.getId());
            }
        });
    }

    // ===== 缺失 Sa-Token 凭据：三个写操作都拒 =====

    @Test
    @DisplayName("删除：缺 Sa-Token 会话 → 拒绝，图片仍存活")
    void deleteRejectedWhenSaTokenMissing() {
        Picture picture = newPublicPicture(owner.getId());
        bind(owner, null);

        assertSpaceNotLogin(() -> pictureService.deletePicture(deleteRequest(picture.getId()), currentRequest()));
        assertNotNull(pictureService.getById(picture.getId()), "被拒后图片仍应存活");
    }

    @Test
    @DisplayName("恢复：缺 Sa-Token 会话 → 拒绝，图片仍在回收站")
    void restoreRejectedWhenSaTokenMissing() {
        Picture picture = newTrashedPublicPicture(owner.getId());
        bind(owner, null);

        assertSpaceNotLogin(() -> pictureService.restorePictures(batch(picture.getId()), currentRequest()));
        assertNotNull(pictureMapper.selectTrashedById(picture.getId()), "被拒后仍在回收站");
    }

    @Test
    @DisplayName("彻底删除：缺 Sa-Token 会话 → 拒绝，行仍在回收站")
    void purgeRejectedWhenSaTokenMissing() {
        Picture picture = newTrashedPublicPicture(owner.getId());
        bind(owner, null);

        assertSpaceNotLogin(() -> pictureService.purgePictures(batch(picture.getId()), currentRequest()));
        assertNotNull(pictureMapper.selectTrashedById(picture.getId()), "被拒后行仍在回收站");
    }

    // ===== 身份不一致：Spring Session 与 Sa-Token 不是同一人 =====

    @Test
    @DisplayName("删除：Sa-Token 与 Spring Session 不是同一人 → 拒绝")
    void deleteRejectedWhenIdentityMismatch() {
        Picture picture = newPublicPicture(owner.getId());
        bind(owner, other);

        assertSpaceNotLogin(() -> pictureService.deletePicture(deleteRequest(picture.getId()), currentRequest()));
        assertNotNull(pictureService.getById(picture.getId()), "被拒后图片仍应存活");
    }

    @Test
    @DisplayName("恢复：Sa-Token 与 Spring Session 不是同一人 → 拒绝")
    void restoreRejectedWhenIdentityMismatch() {
        Picture picture = newTrashedPublicPicture(owner.getId());
        bind(owner, other);

        assertSpaceNotLogin(() -> pictureService.restorePictures(batch(picture.getId()), currentRequest()));
        assertNotNull(pictureMapper.selectTrashedById(picture.getId()), "被拒后仍在回收站");
    }

    // ===== 撤销：Sa-Token 会话已被登出 =====

    @Test
    @DisplayName("恢复：Sa-Token 会话被撤销 → 拒绝")
    void restoreRejectedWhenSaTokenRevoked() {
        Picture picture = newTrashedPublicPicture(owner.getId());
        bind(owner, owner);
        StpKit.SPACE.logout(owner.getId());
        assertNull(StpKit.SPACE.getLoginIdDefaultNull(), "前置：登出后 Sa-Token 登录态应为空");

        assertSpaceNotLogin(() -> pictureService.restorePictures(batch(picture.getId()), currentRequest()));
        assertNotNull(pictureMapper.selectTrashedById(picture.getId()), "被拒后仍在回收站");
    }

    // ===== 改密后旧会话失败（旧 satoken cookie 已随 resetPassword 作废）=====

    @Test
    @DisplayName("删除：改密后旧 satoken 凭据失效 → 拒绝")
    void deleteRejectedAfterPasswordReset() {
        Picture picture = newPublicPicture(resetTarget.getId());
        String oldToken = loginAndCaptureToken(resetTarget);
        resetPassword(resetTarget);
        assertNull(StpKit.SPACE.getSessionByLoginId(resetTarget.getId(), false), "前置：改密后旧 Sa-Token 会话已踢掉");

        // 浏览器仍带着旧 satoken cookie，但服务端会话已作废
        bindWithToken(resetTarget, oldToken);
        assertSpaceNotLogin(() -> pictureService.deletePicture(deleteRequest(picture.getId()), currentRequest()));
        assertNotNull(pictureService.getById(picture.getId()), "被拒后图片仍应存活");
    }

    // ===== 降权后旧会话失败 =====

    @Test
    @DisplayName("删除：降权后旧 satoken 凭据失效 → 拒绝")
    void deleteRejectedAfterRoleDowngrade() {
        Picture picture = newPublicPicture(downgradeTarget.getId());
        String oldToken = loginAndCaptureToken(downgradeTarget);
        // 管理员把目标用户从 admin 降为普通用户（内部会踢掉其 Sa-Token 会话）
        bind(admin, admin);
        UserUpdateRequest updateRequest = new UserUpdateRequest();
        updateRequest.setId(downgradeTarget.getId());
        updateRequest.setUserRole(UserConstant.DEFAULT_ROLE);
        userController.updateUser(updateRequest);
        assertNull(StpKit.SPACE.getSessionByLoginId(downgradeTarget.getId(), false), "前置：降权后旧 Sa-Token 会话已踢掉");

        bindWithToken(downgradeTarget, oldToken);
        assertSpaceNotLogin(() -> pictureService.deletePicture(deleteRequest(picture.getId()), currentRequest()));
        assertNotNull(pictureService.getById(picture.getId()), "被拒后图片仍应存活");
    }

    // ===== 正向：凭据一致时按原有 RBAC 放行（公共 / 私有 / 团队）=====

    @Test
    @DisplayName("删除（公共）：凭据一致 → 放行")
    void deleteSucceedsForPublicWithConsistentSessions() {
        Picture picture = newPublicPicture(owner.getId());
        bind(owner, owner);

        pictureService.deletePicture(deleteRequest(picture.getId()), currentRequest());

        assertNull(pictureService.getById(picture.getId()), "应已进回收站（逻辑删除）");
        assertNotNull(pictureMapper.selectTrashedById(picture.getId()));
    }

    @Test
    @DisplayName("删除（私有）：属主凭据一致 → 放行")
    void deleteSucceedsForPrivateSpaceOwner() {
        Picture picture = newPicture(owner.getId(), privateSpace.getId());
        bind(owner, owner);

        pictureService.deletePicture(deleteRequest(picture.getId()), currentRequest());

        assertNull(pictureService.getById(picture.getId()));
    }

    @Test
    @DisplayName("删除（团队）：有 picture:delete 的成员凭据一致 → 放行")
    void deleteSucceedsForTeamEditor() {
        Picture picture = newPicture(member.getId(), teamSpace.getId());
        bind(member, member);

        pictureService.deletePicture(deleteRequest(picture.getId()), currentRequest());

        assertNull(pictureService.getById(picture.getId()));
    }

    @Test
    @DisplayName("删除（团队）：只有 picture:view 的成员凭据一致 → 无权限拒绝")
    void deleteRejectedForTeamViewer() {
        Picture picture = newPicture(owner.getId(), teamSpace.getId());
        bind(teamViewer, teamViewer);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> pictureService.deletePicture(deleteRequest(picture.getId()), currentRequest()));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
        assertNotNull(pictureService.getById(picture.getId()), "被拒后图片仍应存活");
    }

    @Test
    @DisplayName("恢复/彻底删除（公共）：凭据一致 → 放行")
    void restoreAndPurgeSucceedWithConsistentSessions() {
        Picture toRestore = newTrashedPublicPicture(owner.getId());
        Picture toPurge = newTrashedPublicPicture(owner.getId());
        bind(owner, owner);

        List<PictureTrashResultVO> restored =
                pictureService.restorePictures(batch(toRestore.getId()), currentRequest());
        assertTrue(restored.get(0).isOk());
        assertNotNull(pictureService.getById(toRestore.getId()), "恢复后回到图库");

        List<PictureTrashResultVO> purged =
                pictureService.purgePictures(batch(toPurge.getId()), currentRequest());
        assertTrue(purged.get(0).isOk());
        assertNull(pictureMapper.selectTrashedById(toPurge.getId()), "彻底删除后行消失");
    }

    // ===== 夹具与上下文工具 =====

    private void assertSpaceNotLogin(Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(ErrorCode.SPACE_NOT_LOGIN.getCode(), ex.getCode(),
                "缺失/撤销/身份不一致的凭据必须以 40102 拒绝");
    }

    private DeleteRequest deleteRequest(Long id) {
        DeleteRequest request = new DeleteRequest();
        request.setId(id);
        return request;
    }

    private PictureTrashBatchRequest batch(Long... ids) {
        PictureTrashBatchRequest request = new PictureTrashBatchRequest();
        request.setPictureIdList(List.of(ids));
        return request;
    }

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

    private Space newSpace(String name, int spaceType, long userId) {
        Space space = new Space();
        space.setSpaceName(name + "-" + UUID.randomUUID());
        space.setSpaceType(spaceType);
        space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        space.setMaxSize(SpaceLevelEnum.COMMON.getMaxSize());
        space.setMaxCount(SpaceLevelEnum.COMMON.getMaxCount());
        space.setTotalCount(0L);
        space.setTotalSize(0L);
        space.setUserId(userId);
        spaceService.save(space);
        return space;
    }

    private Picture newPublicPicture(long userId) {
        return newPicture(userId, null);
    }

    private Picture newPicture(long userId, Long spaceId) {
        Picture picture = new Picture();
        picture.setName("trash-guard-" + UUID.randomUUID());
        picture.setUrl("https://mock-cos.test/trash-guard/" + UUID.randomUUID() + ".jpg");
        picture.setPicSize(100L);
        picture.setUserId(userId);
        picture.setSpaceId(spaceId);
        pictureService.save(picture);
        createdPictureIds.add(picture.getId());
        return picture;
    }

    /** 造一条已在回收站的公共图库记录（裸 SQL 置位，绕开被测的写操作） */
    private Picture newTrashedPublicPicture(long userId) {
        Picture picture = newPublicPicture(userId);
        jdbcTemplate.update("UPDATE picture SET isDelete = 1, deleteTime = NOW() WHERE id = ?", picture.getId());
        return picture;
    }

    /** 绑定请求：Spring Session 登录态 +（可选）Sa-Token space 登录态 */
    private void bind(User sessionUser, User satokenUser) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        if (sessionUser != null) {
            request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, sessionUser);
        }
        if (satokenUser != null) {
            StpKit.SPACE.login(satokenUser.getId());
            StpKit.SPACE.getSession().set(UserConstant.USER_LOGIN_STATE, satokenUser);
        }
    }

    /** 绑定请求：Spring Session 登录态 + 携带一个（可能已作废的）satoken cookie */
    private void bindWithToken(User sessionUser, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, sessionUser);
        request.setCookies(new Cookie(SATOKEN_COOKIE, token));
    }

    /** 走真实 login 流程建立两套登录态并返回 satoken 值（供"旧凭据"用例复放） */
    private String loginAndCaptureToken(User user) {
        bind(user, null);
        MockHttpServletRequest request = currentRequest();
        String uuid = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(IMG_CAPTCHA_KEY + uuid, "abcd", 2, TimeUnit.MINUTES);
        UserLoginRequest loginRequest = new UserLoginRequest();
        loginRequest.setUserAccount(user.getUserAccount());
        loginRequest.setUserPassword(RAW_PASSWORD);
        loginRequest.setCaptchaUuid(uuid);
        loginRequest.setCaptchaCode("abcd");
        userService.login(loginRequest, request);
        return StpKit.SPACE.getTokenValue();
    }

    private void resetPassword(User user) {
        stringRedisTemplate.opsForValue().set(EMAIL_CAPTCHA_KEY + user.getEmail(), "123456",
                5, TimeUnit.MINUTES);
        UserPasswordResetRequest resetRequest = new UserPasswordResetRequest();
        resetRequest.setEmail(user.getEmail());
        resetRequest.setEmailCode("123456");
        resetRequest.setNewPassword("newpass4567");
        userService.resetPassword(resetRequest);
    }

    private MockHttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return (MockHttpServletRequest) attributes.getRequest();
    }
}
