package com.lcl.yunpicturebackend.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceQueryRequest;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.SpaceVO;
import com.lcl.yunpicturebackend.enums.SpaceLevelEnum;
import com.lcl.yunpicturebackend.enums.SpaceTypeEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
import com.lcl.yunpicturebackend.service.IUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间列表/详情的可见性回归（docs/plan.md T3.2）。
 * <p>
 * /space/list/page/vo 与 /space/get/vo 此前对匿名与无关用户全量放行，
 * 可枚举他人私有空间的名称、配额、用量与属主。收权后的口径：
 * 列表只返回"本人空间 + 已加入的团队空间"（站点管理员不受限）；详情要求与空间存在归属关系。
 * 空间接口不依赖 Sa-Token 会话（getLoginUser + 回库判权），测试只需 Spring Session 登录态。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpaceListAuthIntegrationTest {

    @Autowired
    private SpaceController spaceController;
    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private ISpaceUserService spaceUserService;
    @Autowired
    private IUserService userService;

    private User owner;
    private User outsider;
    private User viewer;
    private User admin;
    private Space victimPrivateSpace;   // 属主的私有空间
    private Space ownerTeamSpace;       // 属主的团队空间（viewer 已加入）
    private Space outsiderTeamSpace;    // 无关用户自己的团队空间

    @BeforeAll
    void setUpFixtures() {
        owner = newUser("owner", UserConstant.DEFAULT_ROLE);
        outsider = newUser("outsider", UserConstant.DEFAULT_ROLE);
        viewer = newUser("viewer", UserConstant.DEFAULT_ROLE);
        admin = newUser("admin", UserConstant.ADMIN_ROLE);

        victimPrivateSpace = newSpace(owner, SpaceTypeEnum.PRIVATE);
        ownerTeamSpace = newSpace(owner, SpaceTypeEnum.TEAM);
        addMember(ownerTeamSpace, owner, "admin");
        addMember(ownerTeamSpace, viewer, "viewer");
        outsiderTeamSpace = newSpace(outsider, SpaceTypeEnum.TEAM);
        addMember(outsiderTeamSpace, outsider, "admin");
    }

    @AfterAll
    void cleanFixtures() {
        List<Long> spaceIds = List.of(victimPrivateSpace, ownerTeamSpace, outsiderTeamSpace).stream()
                .filter(s -> s != null)
                .map(Space::getId)
                .collect(Collectors.toList());
        spaceUserService.remove(new QueryWrapper<SpaceUser>().in("spaceId", spaceIds));
        spaceIds.forEach(id -> spaceService.removeById(id));
        List.of(owner, outsider, viewer, admin).forEach(u -> {
            if (u != null) {
                userService.removeById(u.getId());
            }
        });
    }

    // ===== 列表接口 =====

    @Test
    void anonymousListShouldBeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> spaceController.listSpaceVOByPage(new SpaceQueryRequest(), newRequest(null)));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
    }

    @Test
    void outsiderShouldOnlySeeOwnSpacesInList() {
        Page<SpaceVO> page = listPage(newRequest(outsider));
        List<Long> ids = page.getRecords().stream().map(SpaceVO::getId).collect(Collectors.toList());
        assertTrue(ids.contains(outsiderTeamSpace.getId()), "应能看到自己的空间");
        assertFalse(ids.contains(victimPrivateSpace.getId()), "不得看到他人私有空间");
        assertFalse(ids.contains(ownerTeamSpace.getId()), "未加入的团队空间不可见");
    }

    @Test
    void viewerShouldSeeJoinedTeamSpaceInList() {
        Page<SpaceVO> page = listPage(newRequest(viewer));
        List<Long> ids = page.getRecords().stream().map(SpaceVO::getId).collect(Collectors.toList());
        assertTrue(ids.contains(ownerTeamSpace.getId()), "已加入的团队空间可见");
        assertFalse(ids.contains(victimPrivateSpace.getId()), "他人私有空间不可见");
    }

    @Test
    void ownerShouldSeeOwnSpacesInList() {
        Page<SpaceVO> page = listPage(newRequest(owner));
        List<Long> ids = page.getRecords().stream().map(SpaceVO::getId).collect(Collectors.toList());
        assertTrue(ids.contains(victimPrivateSpace.getId()));
        assertTrue(ids.contains(ownerTeamSpace.getId()));
    }

    @Test
    void adminShouldSeeAllSpacesInList() {
        Page<SpaceVO> page = listPage(newRequest(admin));
        List<Long> ids = page.getRecords().stream().map(SpaceVO::getId).collect(Collectors.toList());
        assertTrue(ids.contains(victimPrivateSpace.getId()), "站点管理员不受可见范围限制");
    }

    // ===== 详情接口 =====

    @Test
    void anonymousGetVoShouldBeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> spaceController.getSpaceVOById(victimPrivateSpace.getId(), newRequest(null)));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
    }

    @Test
    void outsiderGetVoShouldBeDenied() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> spaceController.getSpaceVOById(victimPrivateSpace.getId(), newRequest(outsider)));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    @Test
    void viewerGetVoShouldPass() {
        BaseResponse<SpaceVO> response =
                spaceController.getSpaceVOById(ownerTeamSpace.getId(), newRequest(viewer));
        assertNotNull(response.getData());
        assertTrue(response.getData().getPermissionList().contains("picture:view"));
    }

    @Test
    void ownerGetVoShouldPass() {
        BaseResponse<SpaceVO> response =
                spaceController.getSpaceVOById(victimPrivateSpace.getId(), newRequest(owner));
        assertNotNull(response.getData());
    }

    // ===== 夹具与工具 =====

    private Page<SpaceVO> listPage(MockHttpServletRequest request) {
        SpaceQueryRequest query = new SpaceQueryRequest();
        query.setCurrent(1);
        query.setPageSize(100);
        BaseResponse<Page<SpaceVO>> response = spaceController.listSpaceVOByPage(query, request);
        return response.getData();
    }

    private User newUser(String name, String role) {
        User user = new User();
        user.setUserAccount(name + "-" + UUID.randomUUID());
        user.setUserPassword("not-used-in-this-test");
        user.setUserName(name);
        user.setUserRole(role);
        userService.save(user);
        return user;
    }

    private Space newSpace(User ownerUser, SpaceTypeEnum spaceType) {
        Space space = new Space();
        space.setSpaceName(spaceType.getText() + "-" + UUID.randomUUID());
        space.setUserId(ownerUser.getId());
        space.setSpaceType(spaceType.getValue());
        space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        space.setMaxSize(SpaceLevelEnum.COMMON.getMaxSize());
        space.setMaxCount(SpaceLevelEnum.COMMON.getMaxCount());
        spaceService.save(space);
        return space;
    }

    private void addMember(Space space, User user, String role) {
        SpaceUser spaceUser = new SpaceUser();
        spaceUser.setSpaceId(space.getId());
        spaceUser.setUserId(user.getId());
        spaceUser.setSpaceRole(role);
        spaceUserService.save(spaceUser);
    }

    private MockHttpServletRequest newRequest(User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (user != null) {
            request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, user);
        }
        return request;
    }
}
