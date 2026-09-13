package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.constant.UserConstant;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

/**
 * Legacy-bug regressions for the uploadPicture replacement path, found while
 * working on docs/plan.md T3.4/T3.5 and user-directed on 2026-09-13:
 * <ol>
 *   <li>The space quota precheck ran before the old picture was resolved, so a
 *       replacement was rejected with the new-upload rules ("空间条数不足"/"空间大小不足")
 *       even though a replacement adds no count and is atomically re-checked by net
 *       size delta inside the transaction. A full space must still allow a
 *       (shrinking/equal) replacement, while a new upload into a full space must
 *       keep being rejected.</li>
 *   <li>getPicture always set userId to the caller, so an admin replacing another
 *       user's picture silently re-owned it. The update branch must keep the
 *       original owner.</li>
 * </ol>
 * Same harness as PictureReplaceQuotaIntegrationTest: real MySQL, mocked CosManager
 * (real JPEG magic header, picSize = temp file size), async cleanup mocked.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PictureReplacePrecheckOwnershipIntegrationTest {

    /** Small max size so a "full space" is easy to build. */
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
    private User admin;
    private Space space;
    private final List<Long> createdPictureIds = new ArrayList<>();

    @BeforeAll
    void setUpFixtures() {
        owner = new User();
        owner.setUserAccount("replace-precheck-" + System.currentTimeMillis());
        owner.setUserPassword(userService.getEncryptPassword("password123"));
        owner.setUserName("replace-precheck-owner");
        userService.save(owner);

        admin = new User();
        admin.setUserAccount("replace-precheck-admin-" + System.currentTimeMillis());
        admin.setUserPassword(userService.getEncryptPassword("password123"));
        admin.setUserName("replace-precheck-admin");
        admin.setUserRole(UserConstant.ADMIN_ROLE);
        userService.save(admin);

        // Private space (spaceType=0): the owner holds every space permission.
        space = new Space();
        space.setSpaceName("replace-precheck-space");
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
        if (admin != null) {
            userService.removeById(admin.getId());
        }
    }

    // ===== Bug 1: full space must not block a replacement (no new count) =====

    @Test
    void replaceInFullSpaceShouldNotBeBlockedByNewUploadPrecheck() {
        PictureVO first = uploadInSpace(null, 500);
        fillSpaceToFull();

        // Replacement adds no count; net size delta GREATEST(1000-500,0)+200=700 <= 1000.
        PictureVO second = uploadInSpace(first.getId(), 200);

        assertEquals(first.getId(), second.getId());
        assertNotEquals(first.getUrl(), second.getUrl());
        assertSpaceQuota(700L, MAX_COUNT);
        Picture after = pictureService.getById(second.getId());
        assertEquals(owner.getId(), after.getUserId());
    }

    // ===== Guard: a new upload into a full space is still rejected =====

    @Test
    void newUploadIntoFullSpaceShouldStillBeRejected() {
        fillSpaceToFull();

        PictureUploadRequest request = new PictureUploadRequest();
        request.setSpaceId(space.getId());
        assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(100), request, owner));

        assertSpaceQuota(MAX_SIZE, MAX_COUNT);
    }

    // ===== Bug 2: admin replacing another user's picture keeps the owner =====

    @Test
    void adminReplaceShouldKeepOriginalOwner() {
        PictureVO first = pictureService.uploadPicture(jpegFile(100), new PictureUploadRequest(), owner);
        createdPictureIds.add(first.getId());

        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(first.getId());
        PictureVO second = pictureService.uploadPicture(jpegFile(200), request, admin);

        assertEquals(first.getId(), second.getId());
        Picture after = pictureService.getById(second.getId());
        assertEquals(owner.getId(), after.getUserId(), "admin replacement must not re-own the picture");
        assertNotEquals(first.getUrl(), second.getUrl());
    }

    // ===== fixtures & helpers =====

    /** Force the space to both count and size caps, as if it were full. */
    private void fillSpaceToFull() {
        Space full = new Space();
        full.setId(space.getId());
        full.setTotalSize(MAX_SIZE);
        full.setTotalCount(MAX_COUNT);
        spaceService.updateById(full);
    }

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
