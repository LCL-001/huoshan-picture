package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.mapper.PictureMapper;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.service.PictureFileCleanupService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 回收站保留期清理回归（T51）：真实库造一条"过期"回收站记录与一条"刚进站"记录，
 * 跑一轮清理后断言过期行被物理删除、刚进站的保留，且过期行的 COS 被清。COS 走 mock。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("回收站保留期清理（真实库）")
class PictureTrashRetentionIntegrationTest {

    private static final int RETENTION_DAYS = 30;
    private static final int BATCH_LIMIT = 200;

    @Autowired
    private IPictureService pictureService;
    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private IUserService userService;
    @Autowired
    private PictureMapper pictureMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private PictureFileCleanupService pictureFileCleanupService;

    private User owner;
    private Space ownerSpace;
    private Picture expiredPicture;
    private Picture freshPicture;
    private final List<Long> createdPictureIds = new ArrayList<>();

    @BeforeAll
    void setUpFixtures() {
        owner = new User();
        owner.setUserAccount("trash-retention-" + System.currentTimeMillis());
        owner.setUserPassword(userService.getEncryptPassword("password123"));
        owner.setUserName("trash-retention");
        owner.setUserRole(UserConstant.DEFAULT_ROLE);
        userService.save(owner);

        ownerSpace = new Space();
        ownerSpace.setSpaceName("trash-retention-space-" + System.currentTimeMillis());
        ownerSpace.setSpaceType(0);
        ownerSpace.setSpaceLevel(0);
        ownerSpace.setUserId(owner.getId());
        spaceService.save(ownerSpace);
    }

    @BeforeEach
    void setUpPerTest() {
        clearInvocations(pictureFileCleanupService);
        expiredPicture = newTrashedPicture();
        freshPicture = newTrashedPicture();
        // 把 expiredPicture 的进站时间回拨到保留期之外
        jdbcTemplate.update("UPDATE picture SET deleteTime = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(RETENTION_DAYS + 1L)), expiredPicture.getId());
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterAll
    void cleanFixtures() {
        createdPictureIds.forEach(id -> jdbcTemplate.update("DELETE FROM picture WHERE id = ?", id));
        if (ownerSpace != null) {
            spaceService.removeById(ownerSpace.getId());
        }
        if (owner != null) {
            userService.removeById(owner.getId());
        }
    }

    @Test
    @DisplayName("过期的彻底删除（含 COS），刚进站的保留")
    void purgesExpiredAndKeepsFresh() {
        int purged = pictureService.purgeExpiredTrash(RETENTION_DAYS, BATCH_LIMIT);

        assertThat(purged).as("至少清掉本次造的过期行").isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM picture WHERE id = ?", Integer.class,
                expiredPicture.getId())).as("过期行已物理删除").isZero();
        assertThat(pictureMapper.selectTrashedById(freshPicture.getId())).as("刚进站的行保留").isNotNull();
        verify(pictureFileCleanupService)
                .clearPictureFile(argThat(p -> expiredPicture.getUrl().equals(p.getUrl())));
    }

    @Test
    @DisplayName("共享 URL 的过期行：同 URL 的另一张仍在回收站 → 不清 COS")
    void expiredPurgeKeepsCosWhenAnotherTrashedReferenceRemains() {
        // expiredPicture 的 deleteTime 已回拨到保留期外；再造一张同 URL 的"刚进站"记录（仍在回收站、可恢复）
        Picture sharer = newTrashedPictureWithUrl(expiredPicture.getUrl());

        int purged = pictureService.purgeExpiredTrash(RETENTION_DAYS, BATCH_LIMIT);

        assertThat(purged).as("至少清掉本次造的过期行").isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM picture WHERE id = ?", Integer.class,
                expiredPicture.getId())).as("过期行已物理删除").isZero();
        assertThat(pictureMapper.selectTrashedById(sharer.getId())).as("同 URL 记录仍留在回收站").isNotNull();
        verify(pictureFileCleanupService, never())
                .clearPictureFile(argThat(p -> expiredPicture.getUrl().equals(p.getUrl())));
    }

    private Picture newTrashedPicture() {
        // 每条夹具独立 URL（互不干扰），见 newTrashedPictureWithUrl
        return newTrashedPictureWithUrl("https://mock-cos.test/retention/" + System.nanoTime() + ".jpg");
    }

    private Picture newTrashedPictureWithUrl(String url) {
        Picture picture = new Picture();
        picture.setUrl(url);
        picture.setName("retention-test-" + System.nanoTime());
        picture.setPicSize(100L);
        picture.setSpaceId(ownerSpace.getId());
        picture.setUserId(owner.getId());
        pictureService.save(picture);
        createdPictureIds.add(picture.getId());

        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, owner);
        // 删除是受 T56 双登录态校验的写操作：同时建立 Sa-Token 登录态
        StpKit.SPACE.login(owner.getId());
        StpKit.SPACE.getSession().set(UserConstant.USER_LOGIN_STATE, owner);
        DeleteRequest deleteRequest = new DeleteRequest();
        deleteRequest.setId(picture.getId());
        pictureService.deletePicture(deleteRequest, request);
        RequestContextHolder.resetRequestAttributes();
        return picture;
    }
}
