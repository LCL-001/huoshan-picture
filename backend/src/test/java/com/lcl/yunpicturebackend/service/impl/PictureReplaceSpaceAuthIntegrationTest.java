package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureUploadRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.CosManager;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
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
 * Space-permission regression for the uploadPicture replacement path (docs/plan.md T3.10).
 * <p>
 * When the request carries only pictureId, the target spaceId is derived from the old
 * picture — and that derived path used to skip the space-level PICTURE_UPLOAD check
 * entirely (only "picture owner or site admin" gated it). A member who had been removed
 * from the space, or demoted to viewer, could still overwrite the file bytes of a picture
 * they once uploaded to a team space. The fix aligns the derived path with the explicit
 * spaceId path and with checkPictureAuth (deletePicture): any space-targeted upload
 * requires PICTURE_UPLOAD via SpaceUserAuthManager.
 * <p>
 * Pinned consequences of the unified rule: a non-member site admin is rejected on team
 * space pictures too (same as the explicit path and deletePicture today), while a real
 * editor member's replacement keeps working.
 * <p>
 * Each test mints its own uploader user and member row (space_user has a unique
 * (spaceId, userId) key, so rows cannot be re-granted across tests) — tests stay
 * order-independent with no shared mutable membership.
 * <p>
 * Same harness as PictureReplaceQuotaIntegrationTest: real MySQL, mocked CosManager
 * (real JPEG magic header, picSize = temp file size), async cleanup mocked.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PictureReplaceSpaceAuthIntegrationTest {

    private static final long MAX_SIZE = 1000L;
    private static final long MAX_COUNT = 10L;

    @Autowired
    private IPictureService pictureService;
    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private ISpaceUserService spaceUserService;
    @Autowired
    private IUserService userService;
    @MockBean
    private CosManager cosManager;
    @MockBean
    private PictureFileCleanupService pictureFileCleanupService;

    private User spaceOwner;
    private User siteAdmin;
    private Space teamSpace;
    private final List<Long> createdPictureIds = new ArrayList<>();
    private final List<User> createdUsers = new ArrayList<>();

    @BeforeAll
    void setUpFixtures() {
        spaceOwner = newUser("spa-replace-owner");
        siteAdmin = newAdmin("spa-replace-siteadmin");
        // Team space; the real createSpace flow gives the owner an admin member row
        // (SpaceServiceImpl#createSpace), mirrored here because Space is saved directly.
        teamSpace = new Space();
        teamSpace.setSpaceName("spa-replace-team-" + System.currentTimeMillis());
        teamSpace.setSpaceType(1);
        teamSpace.setSpaceLevel(0);
        teamSpace.setMaxSize(MAX_SIZE);
        teamSpace.setMaxCount(MAX_COUNT);
        teamSpace.setTotalSize(0L);
        teamSpace.setTotalCount(0L);
        teamSpace.setUserId(spaceOwner.getId());
        spaceService.save(teamSpace);
        SpaceUser ownerMember = new SpaceUser();
        ownerMember.setSpaceId(teamSpace.getId());
        ownerMember.setUserId(spaceOwner.getId());
        ownerMember.setSpaceRole("admin");
        spaceUserService.save(ownerMember);
    }

    @BeforeEach
    void resetPerTest() {
        Space reset = new Space();
        reset.setId(teamSpace.getId());
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
        if (teamSpace != null) {
            spaceUserService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SpaceUser>()
                    .eq("spaceId", teamSpace.getId()));
            spaceService.removeById(teamSpace.getId());
        }
        for (User u : createdUsers) {
            userService.removeById(u.getId());
        }
    }

    // ===== The gap: removed member replaces via pictureId-only (spaceId derived) =====

    @Test
    void removedMemberShouldNotReplaceViaPictureIdOnly() {
        User uploader = newMember("spa-replace-removed", "editor");
        PictureVO first = uploadAs(uploader, 100);
        removeMembership(uploader);

        // No spaceId in the request: the target space comes from the old picture.
        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(first.getId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(200), request, uploader));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    // ===== The gap: member demoted to viewer must not keep upload rights =====

    @Test
    void demotedViewerShouldNotReplaceViaPictureIdOnly() {
        User uploader = newMember("spa-replace-demoted", "editor");
        PictureVO first = uploadAs(uploader, 100);
        SpaceUser demote = new SpaceUser();
        demote.setId(memberRowId(uploader));
        demote.setSpaceRole("viewer");
        spaceUserService.updateById(demote);

        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(first.getId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(200), request, uploader));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    // ===== Unified rule: non-member site admin is rejected, same as deletePicture =====

    @Test
    void nonMemberSiteAdminShouldNotReplaceTeamPicture() {
        User uploader = newMember("spa-replace-admincase", "editor");
        PictureVO first = uploadAs(uploader, 100);

        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(first.getId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(200), request, siteAdmin));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ex.getCode());
    }

    // ===== Guard: a real editor member's replacement keeps working =====

    @Test
    void editorMemberShouldStillReplaceOwnPicture() {
        User uploader = newMember("spa-replace-editor", "editor");
        PictureVO first = uploadAs(uploader, 100);

        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(first.getId());
        PictureVO second = pictureService.uploadPicture(jpegFile(200), request, uploader);
        createdPictureIds.add(second.getId());

        assertEquals(first.getId(), second.getId());
        assertNotEquals(first.getUrl(), second.getUrl());
        Picture after = pictureService.getById(second.getId());
        assertEquals(uploader.getId(), after.getUserId());
    }

    // ===== fixtures & helpers =====

    private User newUser(String prefix) {
        User user = new User();
        user.setUserAccount(prefix + "-" + System.currentTimeMillis());
        user.setUserPassword(userService.getEncryptPassword("password123"));
        user.setUserName(prefix);
        user.setUserRole(UserConstant.DEFAULT_ROLE);
        userService.save(user);
        createdUsers.add(user);
        return user;
    }

    private User newAdmin(String prefix) {
        User user = newUser(prefix);
        user.setUserRole(UserConstant.ADMIN_ROLE);
        userService.updateById(user);
        return user;
    }

    /** Mint a fresh uploader with its own member row (unique key is per space+user). */
    private User newMember(String prefix, String role) {
        User uploader = newUser(prefix);
        SpaceUser member = new SpaceUser();
        member.setSpaceId(teamSpace.getId());
        member.setUserId(uploader.getId());
        member.setSpaceRole(role);
        spaceUserService.save(member);
        return uploader;
    }

    private void removeMembership(User uploader) {
        spaceUserService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SpaceUser>()
                .eq("spaceId", teamSpace.getId())
                .eq("userId", uploader.getId()));
    }

    private Long memberRowId(User uploader) {
        SpaceUser row = spaceUserService.lambdaQuery()
                .eq(SpaceUser::getSpaceId, teamSpace.getId())
                .eq(SpaceUser::getUserId, uploader.getId())
                .one();
        return row.getId();
    }

    private PictureVO uploadAs(User uploader, int fileSize) {
        PictureUploadRequest request = new PictureUploadRequest();
        request.setSpaceId(teamSpace.getId());
        PictureVO picture = pictureService.uploadPicture(jpegFile(fileSize), request, uploader);
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
}
