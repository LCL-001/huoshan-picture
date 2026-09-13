package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.domain.dto.picture.PictureUploadRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.manager.CosManager;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.service.PictureFileCleanupService;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.OriginalInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quota & old-file cleanup regression for picture replacement (docs/plan.md T3.4).
 * The update branch used to charge the space quota unconditionally (+newSize/+1),
 * inflating the quota on every replacement. Acceptance: after replacement the net
 * quota delta must equal (newSize - oldSize), count unchanged; the old COS object
 * must be cleaned only after commit, and never while its URL is still referenced
 * by another record.
 * <p>
 * The real FilePictureUpload template runs against a mocked CosManager: the magic
 * header check demands a real JPEG header and picSize equals the temp file size,
 * so file content length controls the picture size. The async cleanup service is
 * mocked to avoid touching real COS. Quota/DB logic runs on real MySQL.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PictureReplaceQuotaIntegrationTest {

    /** Small max size so an over-quota case is easy to build. */
    private static final long MAX_SIZE = 1000L;
    private static final long MAX_COUNT = 10L;

    @Autowired
    private IPictureService pictureService;
    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private IUserService userService;
    @MockBean
    private CosManager cosManager;
    @MockBean
    private PictureFileCleanupService pictureFileCleanupService;

    private User owner;
    private Space space;
    private final List<Long> createdPictureIds = new ArrayList<>();

    @BeforeAll
    void setUpFixtures() {
        owner = new User();
        owner.setUserAccount("replace-quota-" + System.currentTimeMillis());
        owner.setUserPassword(userService.getEncryptPassword("password123"));
        owner.setUserName("replace-quota-owner");
        userService.save(owner);
        // Private space (spaceType=0): the owner holds every space permission.
        space = new Space();
        space.setSpaceName("replace-quota-space");
        space.setSpaceType(0);
        space.setSpaceLevel(0);
        space.setMaxSize(MAX_SIZE);
        space.setMaxCount(MAX_COUNT);
        space.setTotalSize(0L);
        space.setTotalCount(0L);
        space.setUserId(owner.getId());
        spaceService.save(space);
    }

    @BeforeEach
    void resetPerTest() {
        // The space fixture is reused across tests; zero the quota first.
        Space reset = new Space();
        reset.setId(space.getId());
        reset.setTotalSize(0L);
        reset.setTotalCount(0L);
        spaceService.updateById(reset);
        // ResetMocksTestExecutionListener clears stubs after each test, re-stub here.
        when(cosManager.putPictureObject(anyString(), any(File.class))).thenReturn(fakePutResult());
        clearInvocations(pictureFileCleanupService);
    }

    @AfterAll
    void cleanFixtures() {
        createdPictureIds.forEach(id -> pictureService.removeById(id));
        if (space != null) {
            spaceService.removeById(space.getId());
        }
        if (owner != null) {
            userService.removeById(owner.getId());
        }
    }

    // ===== Acceptance: net quota delta after replacement = newSize - oldSize =====

    @Test
    void replacePictureShouldChargeNetSizeDelta() {
        PictureVO first = uploadInSpace(null, 100);

        PictureVO second = uploadInSpace(first.getId(), 250);

        assertEquals(first.getId(), second.getId());
        assertNotEquals(first.getUrl(), second.getUrl());
        assertSpaceQuota(250L, 1L);
        // The old object lost its reference once the record re-pointed: must be cleaned.
        verify(pictureFileCleanupService, timeout(3000))
                .clearPictureFile(argThat(p -> first.getUrl().equals(p.getUrl())));
    }

    // ===== Replacing with a smaller picture must reduce the quota =====

    @Test
    void replaceWithSmallerPictureShouldReduceQuota() {
        PictureVO first = uploadInSpace(null, 500);

        uploadInSpace(first.getId(), 200);

        assertSpaceQuota(200L, 1L);
    }

    // ===== Over-quota replacement: rollback everything, keep old file =====

    @Test
    void replaceOverQuotaShouldRollbackAndKeepOldFile() {
        PictureVO first = uploadInSpace(null, 100);
        clearInvocations(pictureFileCleanupService);

        // GREATEST(100-100,0)+1001 = 1001 > maxSize 1000: replacement must be rejected.
        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(first.getId());
        request.setSpaceId(space.getId());
        assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(1001), request, owner));

        assertSpaceQuota(100L, 1L);
        Picture after = pictureService.getById(first.getId());
        assertEquals(first.getUrl(), after.getUrl(), "over-quota replacement must roll back entirely");
        verify(pictureFileCleanupService, never()).clearPictureFile(any());
    }

    // ===== Shared URL refcount: never clean a URL still referenced elsewhere =====

    @Test
    void replaceShouldNotCleanupUrlStillReferencedByOtherRecord() {
        PictureVO first = uploadInSpace(null, 100);
        Picture sharer = new Picture();
        sharer.setUrl(first.getUrl());
        sharer.setName("sharer");
        sharer.setPicSize(100L);
        sharer.setUserId(owner.getId());
        sharer.setSpaceId(space.getId());
        pictureService.save(sharer);
        createdPictureIds.add(sharer.getId());

        uploadInSpace(first.getId(), 250);

        assertSpaceQuota(250L, 1L);
        verify(pictureFileCleanupService, never()).clearPictureFile(any());
    }

    // ===== Personal library (no space): no quota involved, old file still cleaned =====

    @Test
    void replacePersonalPictureShouldCleanupWithoutSpaceQuota() {
        PictureVO first = pictureService.uploadPicture(jpegFile(100), new PictureUploadRequest(), owner);
        createdPictureIds.add(first.getId());

        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(first.getId());
        PictureVO second = pictureService.uploadPicture(jpegFile(200), request, owner);

        assertEquals(first.getId(), second.getId());
        assertNull(pictureService.getById(second.getId()).getSpaceId());
        verify(pictureFileCleanupService, timeout(3000))
                .clearPictureFile(argThat(p -> first.getUrl().equals(p.getUrl())));
    }

    // ===== fixtures & helpers =====

    private PictureVO uploadInSpace(Long replaceId, int fileSize) {
        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(replaceId);
        request.setSpaceId(space.getId());
        PictureVO picture = pictureService.uploadPicture(jpegFile(fileSize), request, owner);
        createdPictureIds.add(picture.getId());
        return picture;
    }

    /**
     * JPEG magic header passes the file-header check; content length becomes picSize.
     */
    private static MockMultipartFile jpegFile(int size) {
        byte[] data = new byte[size];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        data[2] = (byte) 0xFF;
        return new MockMultipartFile("file", "a.jpg", "image/jpeg", data);
    }

    /**
     * Image info with an empty process result list, so the template falls into the
     * original-file branch: url = host + "/" + uploadPath, picSize = temp file size.
     */
    private static PutObjectResult fakePutResult() {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setWidth(100);
        imageInfo.setHeight(100);
        imageInfo.setFormat("jpg");
        imageInfo.setAve("0xffffff");
        OriginalInfo originalInfo = new OriginalInfo();
        originalInfo.setImageInfo(imageInfo);
        CIUploadResult ciUploadResult = new CIUploadResult();
        ciUploadResult.setOriginalInfo(originalInfo);
        ciUploadResult.setProcessResults(new ProcessResults());
        PutObjectResult putObjectResult = new PutObjectResult();
        putObjectResult.setCiUploadResult(ciUploadResult);
        return putObjectResult;
    }

    private void assertSpaceQuota(long expectedSize, long expectedCount) {
        Space refreshed = spaceService.getById(space.getId());
        assertEquals(expectedSize, refreshed.getTotalSize(), "space totalSize mismatch");
        assertEquals(expectedCount, refreshed.getTotalCount(), "space totalCount mismatch");
    }
}
