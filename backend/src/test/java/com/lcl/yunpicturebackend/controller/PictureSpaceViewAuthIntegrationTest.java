package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureQueryRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.enums.SpaceLevelEnum;
import com.lcl.yunpicturebackend.enums.SpaceTypeEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.auth.StpInterfaceImpl;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.config.RequestWrapper;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 空间图库读接口的目标绑定鉴权回归（docs/plan.md T3.1）。
 * <p>
 * 用三类真实落库的用户钉住两条攻击路径与三条合法路径：
 * 攻击路径全部以 40101（无权限）收场，合法路径行为不变。
 * 嗅探层的两条攻击（charset 变体令上下文为空、spaceUserId 走私劫持）在 StpInterfaceImpl 层直接断言。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PictureSpaceViewAuthIntegrationTest {

    @Autowired
    private IPictureService pictureService;
    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private ISpaceUserService spaceUserService;
    @Autowired
    private IUserService userService;
    @Autowired
    private StpInterfaceImpl stpInterface;
    @Autowired
    private PictureController pictureController;

    private User owner;        // 受害者：私有空间属主
    private User outsider;     // 攻击者：仅在自己另有归属的团队空间有成员行
    private User viewer;       // 合法成员：受害者空间的 viewer
    private Space victimSpace;
    private Picture victimPicture;
    private Picture publicPicture;
    private Space teamSpace;          // 属主自己的团队空间（viewer 正例用）
    private Picture teamPicture;
    private Long outsiderMemberRowId;

    @BeforeAll
    void setUpFixtures() {
        owner = newUser("owner");
        outsider = newUser("outsider");
        viewer = newUser("viewer");
        // 受害者私有空间 + 一张空间图 + 一张公共图
        victimSpace = new Space();
        victimSpace.setSpaceName("victim-private-" + UUID.randomUUID());
        victimSpace.setUserId(owner.getId());
        victimSpace.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        victimSpace.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        victimSpace.setMaxSize(SpaceLevelEnum.COMMON.getMaxSize());
        victimSpace.setMaxCount(SpaceLevelEnum.COMMON.getMaxCount());
        spaceService.save(victimSpace);
        victimPicture = newPicture(owner.getId(), victimSpace.getId());
        pictureService.save(victimPicture);
        publicPicture = newPicture(owner.getId(), null);
        pictureService.save(publicPicture);
        // 攻击者自己的团队空间成员行（走私 spaceUserId 用的就是它）
        Space outsiderTeamSpace = new Space();
        outsiderTeamSpace.setSpaceName("outsider-team-" + UUID.randomUUID());
        outsiderTeamSpace.setUserId(outsider.getId());
        outsiderTeamSpace.setSpaceType(SpaceTypeEnum.TEAM.getValue());
        outsiderTeamSpace.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        outsiderTeamSpace.setMaxSize(SpaceLevelEnum.COMMON.getMaxSize());
        outsiderTeamSpace.setMaxCount(SpaceLevelEnum.COMMON.getMaxCount());
        spaceService.save(outsiderTeamSpace);
        SpaceUser outsiderMember = new SpaceUser();
        outsiderMember.setSpaceId(outsiderTeamSpace.getId());
        outsiderMember.setUserId(outsider.getId());
        outsiderMember.setSpaceRole("admin");
        spaceUserService.save(outsiderMember);
        outsiderMemberRowId = outsiderMember.getId();
        // 属主团队空间：属主自带 admin 成员行，另放一张空间图供 viewer 正例
        teamSpace = new Space();
        teamSpace.setSpaceName("owner-team-" + UUID.randomUUID());
        teamSpace.setUserId(owner.getId());
        teamSpace.setSpaceType(SpaceTypeEnum.TEAM.getValue());
        teamSpace.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        teamSpace.setMaxSize(SpaceLevelEnum.COMMON.getMaxSize());
        teamSpace.setMaxCount(SpaceLevelEnum.COMMON.getMaxCount());
        spaceService.save(teamSpace);
        SpaceUser ownerMember = new SpaceUser();
        ownerMember.setSpaceId(teamSpace.getId());
        ownerMember.setUserId(owner.getId());
        ownerMember.setSpaceRole("admin");
        spaceUserService.save(ownerMember);
        teamPicture = newPicture(owner.getId(), teamSpace.getId());
        pictureService.save(teamPicture);
    }

    @AfterAll
    void cleanFixtures() {
        if (victimPicture != null) {
            pictureService.removeById(victimPicture.getId());
        }
        if (publicPicture != null) {
            pictureService.removeById(publicPicture.getId());
        }
        if (teamPicture != null) {
            pictureService.removeById(teamPicture.getId());
        }
        if (victimSpace != null) {
            spaceUserService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SpaceUser>()
                    .eq("spaceId", victimSpace.getId()));
            spaceService.removeById(victimSpace.getId());
        }
        if (teamSpace != null) {
            spaceUserService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SpaceUser>()
                    .eq("spaceId", teamSpace.getId()));
            spaceService.removeById(teamSpace.getId());
        }
        for (User u : List.of(owner, outsider, viewer)) {
            if (u != null) {
                userService.removeById(u.getId());
            }
        }
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ===== 攻击路径 1：列表接口 + charset 变体 Content-Type 令嗅探上下文为空（依赖默认授权）=====

    @Test
    void outsiderShouldNotListPrivateSpacePicturesEvenWithCharsetContentType() {
        bindLoggedInRequest(outsider);
        PictureQueryRequest query = new PictureQueryRequest();
        query.setSpaceId(victimSpace.getId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> pictureService.listPictureVOByPage(query, currentRequest()));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    // ===== 攻击路径 2：请求走私自己的 spaceUserId，把授权劫持到自己所在空间 =====

    @Test
    void smuggledSpaceUserIdShouldNotGrantViewOnVictimPicture() {
        bindLoggedInRequest(outsider);
        MockHttpServletRequest request = currentRequest();
        request.setRequestURI("/api/picture/get/vo");
        request.setParameter("id", String.valueOf(victimPicture.getId()));
        request.setParameter("spaceUserId", String.valueOf(outsiderMemberRowId));
        List<String> permissions = stpInterface.getPermissionList(outsider.getId(), StpKit.SPACE_TYPE);
        assertFalse(permissions.contains("picture:view"),
                "pictureId 必须优先于走私的 spaceUserId：授权依据是目标图片的空间归属");
    }

    // ===== 攻击路径 3：charset 变体 JSON body 里的 spaceId 必须真实参与授权 =====

    @Test
    void spaceIdFromCharsetJsonBodyMustBeTargetBound() {
        bindLoggedInRequest(outsider);
        MockHttpServletRequest raw = currentRequest();
        raw.setMethod("POST");
        raw.setRequestURI("/api/picture/list/page/vo");
        raw.setContentType("application/json;charset=UTF-8");
        raw.addHeader("Content-Type", "application/json;charset=UTF-8");
        raw.setContent(("{\"spaceId\":" + victimSpace.getId() + "}").getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new RequestWrapper(raw), response));
        List<String> permissions = stpInterface.getPermissionList(outsider.getId(), StpKit.SPACE_TYPE);
        assertFalse(permissions.contains("picture:view"),
                "带 charset 的 JSON body 必须被嗅探到，spaceId 才能按目标空间判权");
    }

    // ===== 攻击路径 4：详情接口直接越权读取 =====

    @Test
    void outsiderShouldNotGetPrivateSpacePictureDetail() {
        bindLoggedInRequest(outsider);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> pictureController.getPictureVOById(victimPicture.getId(), currentRequest()));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    // ===== 合法路径：属主 / viewer 成员 / 公共图库不受影响 =====

    @Test
    void ownerShouldListOwnPrivateSpace() {
        bindLoggedInRequest(owner);
        PictureQueryRequest query = new PictureQueryRequest();
        query.setSpaceId(victimSpace.getId());
        Page<Picture> page = pictureService.listPictureVOByPage(query, currentRequest());
        assertNotNull(page);
    }

    @Test
    void ownerShouldGetOwnPrivateSpacePictureDetail() {
        bindLoggedInRequest(owner);
        BaseResponse<PictureVO> response = pictureController.getPictureVOById(victimPicture.getId(), currentRequest());
        assertNotNull(response.getData());
    }

    @Test
    void viewerMemberShouldListTeamSpacePictures() {
        // 私有空间按设计只认属主/管理员，成员行不参与判权；
        // viewer 成员正例必须落在团队空间上
        SpaceUser viewerMember = new SpaceUser();
        viewerMember.setSpaceId(teamSpace.getId());
        viewerMember.setUserId(viewer.getId());
        viewerMember.setSpaceRole("viewer");
        spaceUserService.save(viewerMember);
        try {
            bindLoggedInRequest(viewer);
            PictureQueryRequest query = new PictureQueryRequest();
            query.setSpaceId(teamSpace.getId());
            Page<Picture> page = pictureService.listPictureVOByPage(query, currentRequest());
            assertNotNull(page);
        } finally {
            spaceUserService.removeById(viewerMember.getId());
        }
    }

    @Test
    void outsiderCanStillViewPublicPictureDetail() {
        bindLoggedInRequest(outsider);
        BaseResponse<PictureVO> response = pictureController.getPictureVOById(publicPicture.getId(), currentRequest());
        assertNotNull(response.getData());
    }

    // ===== 夹具与上下文工具 =====

    private User newUser(String role) {
        User user = new User();
        user.setUserAccount(role + "-" + UUID.randomUUID());
        user.setUserPassword("not-used-in-this-test");
        user.setUserName(role);
        user.setUserRole(UserConstant.DEFAULT_ROLE);
        userService.save(user);
        return user;
    }

    private Picture newPicture(Long userId, Long spaceId) {
        Picture picture = new Picture();
        picture.setName("pic-" + UUID.randomUUID());
        picture.setUrl("https://example.com/" + UUID.randomUUID() + ".jpg");
        picture.setUserId(userId);
        picture.setSpaceId(spaceId);
        return picture;
    }

    /**
     * 绑定一个已登录的模拟请求：Spring Session 登录态 + Sa-Token space 登录态同时在线，
     * 与 checkSpaceViewPermission 的双登录态一致性检查对齐。
     */
    private void bindLoggedInRequest(User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, user);
        StpKit.SPACE.login(user.getId());
        // 与真实登录流程一致（UserServiceImpl#userLogin）：Sa-Token 会话需写入用户快照，
        // StpInterfaceImpl 的身份判定读取的就是这份快照
        StpKit.SPACE.getSession().set(UserConstant.USER_LOGIN_STATE, user);
    }

    private MockHttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return (MockHttpServletRequest) attributes.getRequest();
    }
}
