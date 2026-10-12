package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureTrashBatchRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashResultVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.auth.SpaceUserAuthManager;
import com.lcl.yunpicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.lcl.yunpicturebackend.manager.cache.PictureListCacheInvalidator;
import com.lcl.yunpicturebackend.mapper.PictureMapper;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.service.PictureFileCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站服务的**拒绝分支**与删除语义（T50）：这些分支在集成测试里不好构造（配额、空间已删、越权），
 * 成功路径交给 {@link PictureTrashIntegrationTest}。纯 Mockito，无 Spring 上下文。
 */
@DisplayName("回收站服务分支（无 Spring）")
class PictureTrashServiceTest {

    private static final Long LOGIN_USER_ID = 1L;

    @Mock
    private IUserService userService;
    @Mock
    private ISpaceService spaceService;
    @Mock
    private ISpaceUserService spaceUserService;
    @Mock
    private SpaceUserAuthManager spaceUserAuthManager;
    @Mock
    private PictureFileCleanupService pictureFileCleanupService;
    @Mock
    private PictureListCacheInvalidator pictureListCacheInvalidator;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private PictureMapper pictureMapper;

    @InjectMocks
    private PictureServiceImpl pictureService;

    private User loginUser;
    private MockHttpServletRequest request;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // ServiceImpl 的 baseMapper 是受保护字段，放不了构造器，只能反射塞
        ReflectionTestUtils.setField(pictureService, "baseMapper", pictureMapper);
        loginUser = new User();
        loginUser.setId(LOGIN_USER_ID);
        loginUser.setUserRole(UserConstant.DEFAULT_ROLE);
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        when(stringRedisTemplate.opsForValue()).thenReturn((ValueOperations<String, String>) org.mockito.Mockito.mock(ValueOperations.class));
        // 让事务模板真的执行回调（否则恢复逻辑整段被跳过）
        when(transactionTemplate.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
        request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, loginUser);
    }

    @Test
    @DisplayName("恢复：记录不在回收站 → 逐条失败，不写真库")
    void restoreFailsWhenRecordNotInTrash() {
        when(pictureMapper.selectTrashedById(9L)).thenReturn(null);

        List<PictureTrashResultVO> results = pictureService.restorePictures(batch(9L), request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(0).getMessage()).contains("不在回收站");
        verify(pictureMapper, never()).restoreTrashedById(any());
    }

    @Test
    @DisplayName("恢复：公共图库里别人的图 → 无权限，不写真库")
    void restoreFailsWithoutPermission() {
        when(pictureMapper.selectTrashedById(9L)).thenReturn(picture(null, 999L, 100L));
        when(userService.isAdmin(any())).thenReturn(false);

        List<PictureTrashResultVO> results = pictureService.restorePictures(batch(9L), request);

        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(0).getMessage()).contains("权限");
        verify(pictureMapper, never()).restoreTrashedById(any());
    }

    @Test
    @DisplayName("恢复：原空间已被删 → 提示空间不存在，不写真库")
    void restoreFailsWhenOriginalSpaceGone() {
        when(pictureMapper.selectTrashedById(9L)).thenReturn(picture(5L, LOGIN_USER_ID, 100L));
        when(spaceService.getById(5L)).thenReturn(null); // checkPictureAuth 查空间为空 → 抛 40400

        List<PictureTrashResultVO> results = pictureService.restorePictures(batch(9L), request);

        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(0).getMessage()).contains("原空间已不存在");
        verify(pictureMapper, never()).restoreTrashedById(any());
    }

    @Test
    @DisplayName("恢复：空间数量配额已满 → 拒绝，记录仍留回收站")
    void restoreFailsWhenQuotaExceeded() {
        Picture trashed = picture(5L, LOGIN_USER_ID, 100L);
        when(pictureMapper.selectTrashedById(9L)).thenReturn(trashed);
        Space space = new Space();
        space.setId(5L);
        space.setSpaceType(0);
        space.setUserId(LOGIN_USER_ID);
        space.setMaxCount(0L);
        space.setTotalCount(0L);
        when(spaceService.getById(5L)).thenReturn(space);
        when(spaceUserAuthManager.getPermissionList(space, loginUser))
                .thenReturn(List.of(SpaceUserPermissionConstant.PICTURE_DELETE));

        List<PictureTrashResultVO> results = pictureService.restorePictures(batch(9L), request);

        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(0).getMessage()).contains("上限");
        verify(pictureMapper, never()).restoreTrashedById(any());
    }

    @Test
    @DisplayName("彻底删除：越权 → 拒绝，不物理删行也不清 COS")
    void purgeFailsWithoutPermission() {
        when(pictureMapper.selectTrashedById(9L)).thenReturn(picture(null, 999L, 100L));
        when(userService.isAdmin(any())).thenReturn(false);

        List<PictureTrashResultVO> results = pictureService.purgePictures(batch(9L), request);

        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(0).getMessage()).contains("权限");
        verify(pictureMapper, never()).purgeTrashedById(any());
        verify(pictureFileCleanupService, never()).clearPictureFile(any());
    }

    @Test
    @DisplayName("批量参数：空列表 / 超过 50 张都被拒")
    void batchIdsValidated() {
        PictureTrashBatchRequest empty = new PictureTrashBatchRequest();
        empty.setPictureIdList(List.of());
        assertThatThrownBy(() -> pictureService.restorePictures(empty, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());

        PictureTrashBatchRequest tooMany = new PictureTrashBatchRequest();
        tooMany.setPictureIdList(LongStream.rangeClosed(1, 51).boxed().toList());
        assertThatThrownBy(() -> pictureService.purgePictures(tooMany, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
    }

    @Test
    @DisplayName("删除：改走软删（写 deleteTime），且**不再即时清 COS**")
    void deletePictureSoftDeletesWithoutCosCleanup() {
        Picture picture = picture(null, LOGIN_USER_ID, 100L);
        picture.setId(3L);
        when(pictureMapper.selectById(3L)).thenReturn(picture);
        when(pictureMapper.softDeleteById(3L)).thenReturn(1);
        DeleteRequest deleteRequest = new DeleteRequest();
        deleteRequest.setId(3L);

        pictureService.deletePicture(deleteRequest, request);

        verify(pictureMapper).softDeleteById(3L);
        verify(pictureMapper, never()).deleteById(any());
        verify(pictureFileCleanupService, never()).clearPictureFile(any());
    }

    private Picture picture(Long spaceId, long userId, long picSize) {
        Picture picture = new Picture();
        picture.setSpaceId(spaceId);
        picture.setUserId(userId);
        picture.setPicSize(picSize);
        picture.setUrl("https://mock-cos.test/unit/" + System.nanoTime() + ".jpg");
        return picture;
    }

    private PictureTrashBatchRequest batch(Long... ids) {
        PictureTrashBatchRequest request = new PictureTrashBatchRequest();
        request.setPictureIdList(List.of(ids));
        return request;
    }
}
