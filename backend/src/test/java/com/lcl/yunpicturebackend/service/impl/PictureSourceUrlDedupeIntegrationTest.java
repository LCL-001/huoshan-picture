package com.lcl.yunpicturebackend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureUploadRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.manager.CosManager;
import com.lcl.yunpicturebackend.manager.upload.URLFilePictureUpload;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * URL 导入按"源地址 + 目标空间"判重（docs/plan.md T29）的回归契约。
 * <p>
 * 改动前：判重拿**源地址**去比对 {@code picture.url}，而库里存的是 COS 地址（host + 随机 key），
 * 两者不同名空间 ⇒ "图片已存在"这条检查对 URL 导入永远命中不了，同一张图会被反复导入。
 * 改动后：来源地址写进独立的 {@code picture.sourceUrl} 列，判重是"源地址 + 空间"，
 * 同一空间内只导一次、跨空间与公共图库互不影响，逻辑删除后可重新导入。
 * <p>
 * 测试替身只换掉两处网络行为（HEAD 预检与下载），其余全是真实链路：真实的
 * {@code uploadPicture} 校验与判重、真实的 COS 上传模板（{@code CosManager} 是 mock）、
 * 真实的事务与 MySQL/Redis。这样"写入来源地址"这条生产逻辑本身也在被验证。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PictureSourceUrlDedupeIntegrationTest {

    private static final String SOURCE_URL = "https://cdn.example.com/origin/photo-1.jpg";
    private static final int JPEG_SIZE = 128;

    @MockBean
    private CosManager cosManager;
    @MockBean
    private PictureFileCleanupService pictureFileCleanupService;

    @Autowired
    private IPictureService pictureService;
    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private IUserService userService;

    private User owner;
    private Space spaceA;
    private Space spaceB;

    @TestConfiguration
    static class LocalUploadTemplateConfig {

        /**
         * 用本地字节流替换 URL 上传模板的网络阶段：HEAD 预检与下载都不出网，
         * 其余（上传路径、COS 调用、结果封装）与生产完全一致。
         */
        @Bean
        @Primary
        URLFilePictureUpload localUrlFilePictureUpload() {
            return new URLFilePictureUpload() {
                @Override
                protected void validPicture(Object inputSource) {
                    // 测试替身：真实的 HEAD 预检会出网并被 SSRF 黑名单拦住，这里跳过
                }

                @Override
                protected void processFile(Object inputSource, File file) throws Exception {
                    byte[] data = new byte[JPEG_SIZE];
                    data[0] = (byte) 0xFF;
                    data[1] = (byte) 0xD8;
                    data[2] = (byte) 0xFF;
                    Files.write(file.toPath(), data);
                }
            };
        }
    }

    @BeforeAll
    void setUpFixtures() {
        owner = new User();
        owner.setUserAccount("dedupe-" + System.currentTimeMillis());
        owner.setUserPassword(userService.getEncryptPassword("password123"));
        owner.setUserName("dedupe-owner");
        userService.save(owner);
        spaceA = newSpace("dedupe-space-a");
        spaceB = newSpace("dedupe-space-b");
    }

    @BeforeEach
    void resetPerTest() {
        pictureService.remove(Wrappers.<Picture>lambdaQuery().eq(Picture::getUserId, owner.getId()));
        // ResetMocksTestExecutionListener 每个用例后会清掉 stub，这里重新打桩
        when(cosManager.putPictureObject(anyString(), any(File.class))).thenReturn(fakePutResult());
    }

    @AfterAll
    void cleanFixtures() {
        pictureService.remove(Wrappers.<Picture>lambdaQuery().eq(Picture::getUserId, owner.getId()));
        List.of(spaceA, spaceB).forEach(space -> spaceService.removeById(space.getId()));
        if (owner != null) {
            userService.removeById(owner.getId());
        }
    }

    // ===== 同一空间：第二次导入被拒，且第一次确实写下了来源地址 =====

    @Test
    void sameSourceUrlInSameSpaceShouldBeRejected() {
        PictureVO first = uploadUrl(SOURCE_URL, spaceA.getId());
        assertEquals(SOURCE_URL, pictureService.getById(first.getId()).getSourceUrl(),
                "URL 导入应把来源地址写进 sourceUrl");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> uploadUrl(SOURCE_URL, spaceA.getId()));
        assertEquals("图片已存在", exception.getMessage());

        assertEquals(1L, countBySourceUrl(SOURCE_URL, spaceA.getId()));
    }

    // ===== 不同空间：同一张图可以各存一份 =====

    @Test
    void sameSourceUrlInDifferentSpacesShouldBothSucceed() {
        uploadUrl(SOURCE_URL, spaceA.getId());
        uploadUrl(SOURCE_URL, spaceB.getId());

        assertEquals(1L, countBySourceUrl(SOURCE_URL, spaceA.getId()));
        assertEquals(1L, countBySourceUrl(SOURCE_URL, spaceB.getId()));
    }

    // ===== 公共图库与空间互不影响（公共图库 = spaceId 为空） =====

    @Test
    void sameSourceUrlInPublicLibraryAndSpaceShouldBothSucceed() {
        PictureVO inPublic = uploadUrl(SOURCE_URL, null);
        PictureVO inSpace = uploadUrl(SOURCE_URL, spaceA.getId());

        assertNull(pictureService.getById(inPublic.getId()).getSpaceId(), "公共图库导入的 spaceId 应为空");
        assertEquals(spaceA.getId(), pictureService.getById(inSpace.getId()).getSpaceId());
        assertEquals(1L, countBySourceUrl(SOURCE_URL, null));
        assertEquals(1L, countBySourceUrl(SOURCE_URL, spaceA.getId()));
    }

    // ===== 逻辑删除后可以重新导入 =====

    @Test
    void logicallyDeletedPictureCanBeReimported() {
        PictureVO first = uploadUrl(SOURCE_URL, spaceA.getId());
        pictureService.removeById(first.getId());

        uploadUrl(SOURCE_URL, spaceA.getId());

        assertEquals(1L, countBySourceUrl(SOURCE_URL, spaceA.getId()));
    }

    // ===== 用同一个源地址替换自己：不能被自己挡住 =====

    @Test
    void replaceWithSameSourceUrlShouldNotBeBlockedByItself() {
        PictureVO first = uploadUrl(SOURCE_URL, spaceA.getId());

        PictureUploadRequest replaceRequest = new PictureUploadRequest();
        replaceRequest.setId(first.getId());
        replaceRequest.setSpaceId(spaceA.getId());
        PictureVO replaced = pictureService.uploadPicture(SOURCE_URL, replaceRequest, owner);

        assertEquals(first.getId(), replaced.getId(), "替换应更新原记录而不是新增");
        Picture after = pictureService.getById(first.getId());
        assertEquals(SOURCE_URL, after.getSourceUrl());
        assertEquals(1L, countBySourceUrl(SOURCE_URL, spaceA.getId()));
    }

    // ===== 用文件替换后，旧来源地址必须清空，否则会继续误挡 =====

    @Test
    void replacingWithUploadedFileShouldClearSourceUrl() {
        PictureVO imported = uploadUrl(SOURCE_URL, spaceA.getId());
        assertEquals(SOURCE_URL, pictureService.getById(imported.getId()).getSourceUrl());

        PictureUploadRequest replaceRequest = new PictureUploadRequest();
        replaceRequest.setId(imported.getId());
        replaceRequest.setSpaceId(spaceA.getId());
        pictureService.uploadPicture(jpegFile(), replaceRequest, owner);

        assertNull(pictureService.getById(imported.getId()).getSourceUrl(),
                "文件替换后该记录不再有来源地址");
        // 清空之后，同一源地址可以重新导入（否则旧地址会一直挡住它）
        uploadUrl(SOURCE_URL, spaceA.getId());
        assertEquals(1L, countBySourceUrl(SOURCE_URL, spaceA.getId()));
    }

    // ===== fixtures & helpers =====

    private Space newSpace(String name) {
        Space space = new Space();
        space.setSpaceName(name);
        space.setSpaceType(0);
        space.setSpaceLevel(0);
        space.setMaxSize(10_000_000L);
        space.setMaxCount(100L);
        space.setTotalSize(0L);
        space.setTotalCount(0L);
        space.setUserId(owner.getId());
        spaceService.save(space);
        return space;
    }

    private PictureVO uploadUrl(String sourceUrl, Long spaceId) {
        PictureUploadRequest request = new PictureUploadRequest();
        request.setSpaceId(spaceId);
        request.setPicName("dedupe");
        return pictureService.uploadPicture(sourceUrl, request, owner);
    }

    private long countBySourceUrl(String sourceUrl, Long spaceId) {
        return pictureService.lambdaQuery()
                .eq(Picture::getSourceUrl, sourceUrl)
                .eq(spaceId != null, Picture::getSpaceId, spaceId)
                .isNull(spaceId == null, Picture::getSpaceId)
                .count();
    }

    /**
     * JPEG magic header passes the file-header check; content length becomes picSize.
     */
    private static MockMultipartFile jpegFile() {
        byte[] data = new byte[JPEG_SIZE];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        data[2] = (byte) 0xFF;
        return new MockMultipartFile("file", "a.jpg", "image/jpeg", data);
    }

    /**
     * Image info with an empty process result list, so the template falls into the
     * original-file branch: url = host + "/" + uploadPath.
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
