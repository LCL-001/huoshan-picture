package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureTrashBatchRequest;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureTrashQueryRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashResultVO;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashVO;
import com.lcl.yunpicturebackend.mapper.PictureMapper;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 回收站回归（T50）：删除 → 进回收站（COS 延迟清理）→ 恢复（名额回补）→ 彻底删除（物理删行 + 清 COS），
 * 以及归属过滤与配额/权限两类拒绝分支。真实 MySQL + Redis；COS 清理走 mock。
 * <p>
 * 用**私有**空间：属主经 SpaceUserAuthManager 直接拿 admin 权限（含 picture:delete），
 * 不像团队空间那样依赖 space_user 行。公共图库那张用 spaceId=null 覆盖另一条权限分支。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("回收站（真实库）")
class PictureTrashIntegrationTest {

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
    private User other;
    private User admin;
    private Space ownerSpace;
    private Picture picInSpace;
    private Picture picPublic;
    private Picture picPublicOther;
    private final List<Long> createdPictureIds = new ArrayList<>();

    @BeforeAll
    void setUpFixtures() {
        owner = newUser("trash-owner", UserConstant.DEFAULT_ROLE);
        other = newUser("trash-other", UserConstant.DEFAULT_ROLE);
        admin = newUser("trash-admin", UserConstant.ADMIN_ROLE);
        ownerSpace = newSpace("trash-owner-space", owner.getId());
    }

    @BeforeEach
    void setUpPerTest() {
        clearInvocations(pictureFileCleanupService);
        resetSpaceCounters();
        // 每例重建图片：删除/彻底删除会改掉夹具状态，跨例共享会互相污染（后一例就 NOT_FOUND 了）
        picInSpace = newPicture(ownerSpace.getId(), owner.getId(), 100L);
        picPublic = newPicture(null, owner.getId(), 100L);
        picPublicOther = newPicture(null, other.getId(), 100L);
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterAll
    void cleanFixtures() {
        // 图片可能已被物理删；用裸 SQL 兜底清干净，避免 isDelete=1 残留污染后续 admin 回收站列表
        createdPictureIds.forEach(id -> jdbcTemplate.update("DELETE FROM picture WHERE id = ?", id));
        if (ownerSpace != null) {
            spaceService.removeById(ownerSpace.getId());
        }
        List.of(owner, other, admin).forEach(user -> {
            if (user != null) {
                userService.removeById(user.getId());
            }
        });
    }

    @Test
    @DisplayName("删除：软删进回收站 + 写 deleteTime + 扣名额 + 不即时清 COS")
    void deleteGoesToTrashAndDefersCosCleanup() {
        bindRequest(owner);
        delete(picInSpace.getId());

        assertThat(pictureService.getById(picInSpace.getId())).as("普通查询查不到（逻辑删除）").isNull();
        Picture trashed = pictureMapper.selectTrashedById(picInSpace.getId());
        assertThat(trashed).as("裸 SQL 能查到回收站里的行").isNotNull();
        assertThat(trashed.getDeleteTime()).as("deleteTime 已写入").isNotNull();
        assertThat(spaceService.getById(ownerSpace.getId()).getTotalCount()).isEqualTo(0L);
        assertThat(spaceService.getById(ownerSpace.getId()).getTotalSize()).isEqualTo(0L);
        verify(pictureFileCleanupService, never()).clearPictureFile(any());
    }

    @Test
    @DisplayName("恢复：回到图库 + 名额回补 + 从回收站消失")
    void restoreBringsBackAndRefillsSpaceQuota() {
        bindRequest(owner);
        delete(picInSpace.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT totalCount FROM space WHERE id = ?", Long.class,
                ownerSpace.getId())).isEqualTo(0L);

        List<PictureTrashResultVO> results =
                pictureService.restorePictures(batch(picInSpace.getId()), currentRequest());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(pictureService.getById(picInSpace.getId())).isNotNull();
        assertThat(pictureMapper.selectTrashedById(picInSpace.getId())).isNull();
        Space after = spaceService.getById(ownerSpace.getId());
        assertThat(after.getTotalCount()).isEqualTo(1L);
        assertThat(after.getTotalSize()).isEqualTo(100L);
        verify(pictureFileCleanupService, never()).clearPictureFile(any());
    }

    @Test
    @DisplayName("彻底删除：物理删行 + 引用计数为 0 时清 COS")
    void purgePhysicallyDeletesRowAndCleansCos() {
        bindRequest(owner);
        delete(picInSpace.getId());

        List<PictureTrashResultVO> results =
                pictureService.purgePictures(batch(picInSpace.getId()), currentRequest());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(pictureMapper.selectTrashedById(picInSpace.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM picture WHERE id = ?", Integer.class,
                picInSpace.getId())).isZero();
        verify(pictureFileCleanupService).clearPictureFile(argThat(p -> picInSpace.getUrl().equals(p.getUrl())));
    }

    @Test
    @DisplayName("列表：普通用户只看自己的，管理员看全部")
    void trashListShowsOwnOnlyUnlessAdmin() {
        bindRequest(owner);
        delete(picPublic.getId());
        bindRequest(admin);
        delete(picPublicOther.getId());

        bindRequest(owner);
        List<Long> ownerSees = idsIn(pictureService.listTrash(new PictureTrashQueryRequest(), currentRequest()));
        assertThat(ownerSees).as("看得到自己删的公共图库图片").contains(picPublic.getId());
        assertThat(ownerSees).as("看不到别人删的").doesNotContain(picPublicOther.getId());

        bindRequest(admin);
        List<Long> adminSees = idsIn(pictureService.listTrash(new PictureTrashQueryRequest(), currentRequest()));
        assertThat(adminSees).contains(picPublic.getId(), picPublicOther.getId());
    }

    @Test
    @DisplayName("恢复：空间配额不足时拒绝，记录仍留在回收站")
    void restoreRejectedWhenSpaceQuotaExceeded() {
        bindRequest(owner);
        delete(picInSpace.getId());
        // 删除后 totalCount=0；把上限压到 0，恢复必然超额
        spaceService.lambdaUpdate().eq(Space::getId, ownerSpace.getId()).set(Space::getMaxCount, 0L).update();

        List<PictureTrashResultVO> results =
                pictureService.restorePictures(batch(picInSpace.getId()), currentRequest());

        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(0).getMessage()).contains("上限");
        assertThat(pictureService.getById(picInSpace.getId())).isNull();
        assertThat(pictureMapper.selectTrashedById(picInSpace.getId())).isNotNull();
        assertThat(spaceService.getById(ownerSpace.getId()).getTotalCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("恢复：无删除权限的用户被拒，记录仍留在回收站")
    void restoreRejectedForUserWithoutDeletePermission() {
        bindRequest(owner);
        delete(picInSpace.getId());

        bindRequest(other);
        List<PictureTrashResultVO> results =
                pictureService.restorePictures(batch(picInSpace.getId()), currentRequest());

        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(0).getMessage()).contains("权限");
        assertThat(pictureService.getById(picInSpace.getId())).isNull();
    }

    // ===== 夹具与工具 =====

    private void delete(Long pictureId) {
        DeleteRequest request = new DeleteRequest();
        request.setId(pictureId);
        pictureService.deletePicture(request, currentRequest());
    }

    private PictureTrashBatchRequest batch(Long... ids) {
        PictureTrashBatchRequest request = new PictureTrashBatchRequest();
        request.setPictureIdList(Arrays.asList(ids));
        return request;
    }

    private List<Long> idsIn(Page<PictureTrashVO> page) {
        return page.getRecords().stream().map(PictureTrashVO::getId).toList();
    }

    private void resetSpaceCounters() {
        // 让每个用例从确定的计数器起点开始（save 时不记账，故落库后手工置零；上限给足）
        spaceService.lambdaUpdate()
                .eq(Space::getId, ownerSpace.getId())
                .setSql("totalCount = 0, totalSize = 0, maxCount = 100, maxSize = 1073741824")
                .update();
    }

    private User newUser(String name, String role) {
        User user = new User();
        user.setUserAccount(name + "-" + System.currentTimeMillis());
        user.setUserPassword(userService.getEncryptPassword("password123"));
        user.setUserName(name);
        user.setUserRole(role);
        userService.save(user);
        return user;
    }

    private Space newSpace(String name, long userId) {
        Space space = new Space();
        space.setSpaceName(name + "-" + System.currentTimeMillis());
        space.setSpaceType(0);
        space.setSpaceLevel(0);
        space.setMaxCount(100L);
        space.setMaxSize(1024L * 1024 * 1024);
        space.setTotalCount(0L);
        space.setTotalSize(0L);
        space.setUserId(userId);
        spaceService.save(space);
        return space;
    }

    private Picture newPicture(Long spaceId, long userId, long picSize) {
        Picture picture = new Picture();
        picture.setUrl("https://mock-cos.test/trash/" + System.nanoTime() + ".jpg");
        picture.setName("trash-test-" + System.nanoTime());
        picture.setPicSize(picSize);
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
