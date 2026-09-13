package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.controller.UserController;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.service.PictureFileCleanupService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 删除空间 / 删除用户的级联回归（docs/plan.md T3.5）：
 * 删空间曾只删空间行与成员记录，空间下图片既不逻辑删也不清 COS，且两步删除没有事务包裹。
 * 验收口径：删空间后图片逻辑删除、无存活 URL 引用的图片文件被送清理；删号后名下空间
 * 级联、在他人团队空间的成员关系移除、上传到他人空间的图片保留（团队内容不随账号消失）。
 * <p>
 * COS 清理走 PictureFileCleanupService mock，避免真实删除对象；数据库走真实 MySQL。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpaceDeleteCascadeIntegrationTest {

    /** 跨空间共享的图片 URL：同一次运行内两行共用，验证引用计数挡住文件清理 */
    private static final String SHARED_URL = "https://mock-cos.test/shared-url.jpg";

    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private ISpaceUserService spaceUserService;
    @Autowired
    private IPictureService pictureService;
    @Autowired
    private IUserService userService;
    @Autowired
    private UserController userController;
    @MockBean
    private PictureFileCleanupService pictureFileCleanupService;

    private User admin;
    private User owner;
    private User member;
    private User otherOwner;
    private User deletedUser;

    private Space ownerSpace;
    private Space teamSpace;
    private Space deletedUserSpace;
    private Picture ownerOnlyPicture;
    private Picture sharedInOwnerSpace;
    private Picture sharedInTeamSpace;
    private Picture deletedUserPicture;
    private Picture deletedUserUploadInTeamSpace;
    private final List<Long> createdPictureIds = new ArrayList<>();

    @BeforeAll
    void setUpFixtures() {
        admin = newUser("cascade-admin", UserConstant.ADMIN_ROLE);
        owner = newUser("cascade-owner", UserConstant.DEFAULT_ROLE);
        member = newUser("cascade-member", UserConstant.DEFAULT_ROLE);
        otherOwner = newUser("cascade-other-owner", UserConstant.DEFAULT_ROLE);
        deletedUser = newUser("cascade-deleted", UserConstant.DEFAULT_ROLE);

        // owner 的团队空间：含一名成员，两张图（其一 URL 与 teamSpace 里的图共享）
        ownerSpace = newSpace("owner-team-space", 1, owner.getId());
        teamSpace = newSpace("other-team-space", 1, otherOwner.getId());
        deletedUserSpace = newSpace("deleted-private-space", 0, deletedUser.getId());

        spaceUserService.save(new SpaceUser()
                .setSpaceId(ownerSpace.getId()).setUserId(member.getId()).setSpaceRole("viewer"));
        spaceUserService.save(new SpaceUser()
                .setSpaceId(teamSpace.getId()).setUserId(deletedUser.getId()).setSpaceRole("viewer"));

        ownerOnlyPicture = newPicture("https://mock-cos.test/" + ownerSpace.getId() + "/unique-owner-"
                + System.nanoTime() + ".jpg", ownerSpace.getId(), owner.getId());
        sharedInOwnerSpace = newPicture(SHARED_URL, ownerSpace.getId(), owner.getId());
        sharedInTeamSpace = newPicture(SHARED_URL, teamSpace.getId(), otherOwner.getId());
        deletedUserPicture = newPicture("https://mock-cos.test/" + deletedUserSpace.getId() + "/deleted-user-"
                + System.nanoTime() + ".jpg", deletedUserSpace.getId(), deletedUser.getId());
        // 被删用户上传到他人团队空间的图片：账号删除后必须保留
        deletedUserUploadInTeamSpace = newPicture("https://mock-cos.test/" + teamSpace.getId() + "/kept-upload-"
                + System.nanoTime() + ".jpg", teamSpace.getId(), deletedUser.getId());
    }

    @BeforeEach
    void resetPerTest() {
        clearInvocations(pictureFileCleanupService);
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterAll
    void cleanFixtures() {
        List.of(ownerSpace, teamSpace, deletedUserSpace).forEach(space -> {
            if (space != null) {
                spaceService.removeById(space.getId());
            }
        });
        createdPictureIds.forEach(id -> pictureService.removeById(id));
        List.of(admin, owner, member, otherOwner, deletedUser).forEach(user -> {
            if (user != null) {
                userService.removeById(user.getId());
            }
        });
    }

    // ===== 删空间：图片逻辑删 + 无存活引用的图片送清理 + 成员记录清理，团队空间不受影响 =====

    @Test
    void deleteSpaceShouldCascadePicturesAndMembers() {
        bindRequest(owner);
        DeleteRequest request = new DeleteRequest();
        request.setId(ownerSpace.getId());
        spaceService.deleteSpace(request, currentRequest());

        assertNull(spaceService.getById(ownerSpace.getId()), "空间行应被逻辑删除");
        assertEquals(0L, spaceUserService.lambdaQuery()
                .eq(SpaceUser::getSpaceId, ownerSpace.getId()).count(), "成员记录应被清理");
        assertNull(pictureService.getById(ownerOnlyPicture.getId()), "空间图片应被逻辑删除");
        assertNull(pictureService.getById(sharedInOwnerSpace.getId()), "空间图片应被逻辑删除");
        // 团队空间里的共享 URL 图仍存活：ownerSpace 中该 URL 的图不得触发文件清理
        verify(pictureFileCleanupService, timeout(3000))
                .clearPictureFile(argThat(p -> ownerOnlyPicture.getUrl().equals(p.getUrl())));
        verify(pictureFileCleanupService, never())
                .clearPictureFile(argThat(p -> sharedInTeamSpace.getUrl().equals(p.getUrl())));
        // 其他空间不受影响
        assertNotNull(spaceService.getById(teamSpace.getId()));
        assertNotNull(pictureService.getById(sharedInTeamSpace.getId()));
    }

    // ===== 删用户：名下空间级联、他人空间成员关系移除、他人空间内容保留 =====

    @Test
    void deleteUserShouldCascadeOwnedSpacesAndMemberships() {
        bindRequest(admin);
        DeleteRequest request = new DeleteRequest();
        request.setId(deletedUser.getId());
        userController.deleteUser(request);

        assertNull(userService.getById(deletedUser.getId()), "账号行应被逻辑删除");
        assertNull(spaceService.getById(deletedUserSpace.getId()), "名下空间应被级联删除");
        assertNull(pictureService.getById(deletedUserPicture.getId()), "名下空间图片应被逻辑删除");
        verify(pictureFileCleanupService, timeout(3000))
                .clearPictureFile(argThat(p -> deletedUserPicture.getUrl().equals(p.getUrl())));
        assertEquals(0L, spaceUserService.lambdaQuery()
                .eq(SpaceUser::getSpaceId, teamSpace.getId())
                .eq(SpaceUser::getUserId, deletedUser.getId()).count(), "他人空间的成员关系应被移除");
        // 他人团队空间与其内容（含被删用户上传的图）必须原样保留
        assertNotNull(spaceService.getById(teamSpace.getId()));
        assertNotNull(pictureService.getById(sharedInTeamSpace.getId()));
        assertNotNull(pictureService.getById(deletedUserUploadInTeamSpace.getId()),
                "上传到他人空间的图片不应随账号删除");
        assertNotNull(userService.getById(otherOwner.getId()));
    }

    // ===== 夹具与工具 =====

    private User newUser(String name, String role) {
        User user = new User();
        user.setUserAccount(name + "-" + System.currentTimeMillis());
        user.setUserPassword(userService.getEncryptPassword("password123"));
        user.setUserName(name);
        user.setUserRole(role);
        userService.save(user);
        return user;
    }

    private Space newSpace(String name, int spaceType, long userId) {
        Space space = new Space();
        space.setSpaceName(name);
        space.setSpaceType(spaceType);
        space.setSpaceLevel(0);
        space.setUserId(userId);
        spaceService.save(space);
        return space;
    }

    private Picture newPicture(String url, long spaceId, long userId) {
        Picture picture = new Picture();
        picture.setUrl(url);
        picture.setName("cascade-test-" + spaceId + "-" + System.nanoTime());
        picture.setPicSize(100L);
        picture.setSpaceId(spaceId);
        picture.setUserId(userId);
        pictureService.save(picture);
        createdPictureIds.add(picture.getId());
        return picture;
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
