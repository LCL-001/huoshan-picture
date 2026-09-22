package com.lcl.yunpicturebackend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureUploadByBatchRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.cache.PictureListCacheInvalidator;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.service.PictureFileCleanupService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 批量抓取落库批量化（docs/plan.md T28）的回归契约。
 * <p>
 * 改动前：并行线程里调的是完整的 {@code uploadPicture}，每张图各自一个事务、各自清一次列表缓存；
 * 改动后：并行线程只做"下载 + COS 转存 + 构造未落库记录"，{@code allOf} 之后一次 {@code saveBatch}、
 * 一次清缓存，入库失败整批回滚并补偿删掉已转存的文件。
 * <p>
 * 两个测试缝都是 {@code protected} 方法，用 {@code @SpyBean} 替换（与
 * {@code CacheRebuildLockIntegrationTest#loadFromDb} 同一做法）：外网抓页与逐张下载都换成测试替身，
 * 真实跑的是去重、批量入库、清缓存与失败补偿这段逻辑，DB/Redis 用本地真实实例。
 * <p>
 * 用例分工：前三个用例钉住"批量化"的收益（一次清缓存、批内重复源地址只存一份、单张失败不拖垮整批），
 * 第四个用例钉住"整批原子"这条已确认的语义变更（代价：入库失败时整批已转存的文件都要补偿删除）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PictureBatchUploadIntegrationTest {

    @SpyBean
    private PictureServiceImpl pictureService;
    @Autowired
    private IPictureService pictureServiceForQuery;
    @Autowired
    private IUserService userService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private PictureFileCleanupService pictureFileCleanupService;
    @MockBean
    private PictureListCacheInvalidator pictureListCacheInvalidator;

    private User uploader;

    /**
     * 测试替身返回的 COS 地址（= 会真正落库的那些 url），用于精确统计落库条数。
     * 并行阶段在多个线程里写入，必须用线程安全容器
     */
    private final List<String> storedUrls = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void setUpUploader() {
        uploader = new User();
        uploader.setUserAccount("batch-upload-" + System.currentTimeMillis());
        uploader.setUserPassword(userService.getEncryptPassword("password123"));
        uploader.setUserName("batch-upload-admin");
        userService.save(uploader);
    }

    @BeforeEach
    void resetPerTest() {
        // 上一个用例留下的记录按上传人清掉，落库断言只认本用例的地址
        pictureServiceForQuery.remove(Wrappers.<Picture>lambdaQuery().eq(Picture::getUserId, uploader.getId()));
        storedUrls.clear();
    }

    @AfterAll
    void cleanFixtures() {
        pictureServiceForQuery.remove(Wrappers.<Picture>lambdaQuery().eq(Picture::getUserId, uploader.getId()));
        if (uploader != null) {
            userService.removeById(uploader.getId());
        }
    }

    // ===== 用例 1：N 张图只落一次库、只清一次列表缓存 =====

    @Test
    void batchUploadShouldStoreAllAndClearListCacheOnce() {
        stubSearchPage("https://img.example.com/a.jpg", "https://img.example.com/b.jpg",
                "https://img.example.com/c.jpg", "https://img.example.com/d.jpg",
                "https://img.example.com/e.jpg");
        stubPreparePicture(call -> newPicture());

        int uploaded = pictureService.uploadPictureByBatch(batchRequest(5), uploader);

        assertEquals(5, uploaded);
        assertEquals(5L, countStoredPictures());
        // 逐张清缓存会让 5 张图触发 5 次版本号自增与 5 次失效广播，批量期间列表缓存被反复打穿
        verify(pictureListCacheInvalidator, times(1)).invalidateAll();
    }

    // ===== 用例 2：同一批里重复的源地址只下载、只入库一次 =====

    @Test
    void duplicateSourceUrlWithinBatchShouldBePreparedAndStoredOnce() {
        stubSearchPage("https://img.example.com/dup.jpg", "https://img.example.com/uniq.jpg",
                "https://img.example.com/dup.jpg");
        stubPreparePicture(call -> newPicture());

        int uploaded = pictureService.uploadPictureByBatch(batchRequest(3), uploader);

        assertEquals(2, uploaded, "duplicate source url should be deduplicated, only one copy kept");
        assertEquals(2L, countStoredPictures());
        verify(pictureService, times(2)).preparePicture(any(), any(), any(), any(), any());
    }

    // ===== 用例 3：单张下载失败仍然只跳过该张，不拖垮整批 =====

    @Test
    void failedSingleDownloadShouldNotFailTheBatch() {
        stubSearchPage("https://img.example.com/ok1.jpg", "https://img.example.com/broken.jpg",
                "https://img.example.com/ok2.jpg");
        stubPreparePicture(call -> call == 2 ? null : newPicture());

        int uploaded = pictureService.uploadPictureByBatch(batchRequest(3), uploader);

        assertEquals(2, uploaded);
        assertEquals(2L, countStoredPictures());
        // 三张都尝试过下载（一张失败），失败的只跳过自己
        verify(pictureService, times(3)).preparePicture(anyString(), any(), any(), any(), any());
    }

    // ===== 用例 4：入库阶段整批原子——一行违反约束则整批回滚，已转存的文件全部补偿删除 =====

    @Test
    void batchInsertFailureShouldRollBackWholeBatchAndCompensateUploadedFiles() {
        stubSearchPage("https://img.example.com/p1.jpg", "https://img.example.com/p2.jpg",
                "https://img.example.com/p3.jpg");
        // 第 2 张造一个违反非空约束的记录（user_id 无默认值），模拟"任意一行落库失败"
        stubPreparePicture(call -> call == 2 ? newPicture().setUserId(null) : newPicture());

        PictureUploadByBatchRequest request = batchRequest(3);
        assertThrows(RuntimeException.class, () -> pictureService.uploadPictureByBatch(request, uploader),
                "persist stage must fail loudly instead of returning a smaller success count");

        assertEquals(0L, countStoredPictures(), "whole batch must roll back: no record stored");
        // 三张图都已转存 COS 却没有任何记录引用它们：全部补偿删除
        verify(pictureFileCleanupService, timeout(3000).times(3)).clearPictureFile(any());
    }

    // ===== 用例 5：JDBC 参数 rewriteBatchedStatements 真的把 3 行合成 1 条 INSERT =====

    @Test
    void batchInsertShouldMergeIntoOneStatement() {
        stubSearchPage("https://img.example.com/m1.jpg", "https://img.example.com/m2.jpg",
                "https://img.example.com/m3.jpg");
        stubPreparePicture(call -> newPicture());
        long insertStatementsBefore = comInsertCount();

        pictureService.uploadPictureByBatch(batchRequest(3), uploader);

        // Com_insert 统计的是"语句条数"：驱动把批处理改写成多值 INSERT 时 3 行只算 1 条，
        // 没改写（缺少 rewriteBatchedStatements=true）则是 3 条
        assertEquals(1L, comInsertCount() - insertStatementsBefore,
                "rewriteBatchedStatements=true 未生效：3 行被拆成了多条 INSERT");
    }

    // ===== 测试替身与工具方法 =====

    /**
     * 替换外网抓页：返回一份最小可解析的 Bing 结果页（容器/条目/元数据属性与真实页面同构）
     */
    private void stubSearchPage(String... imageUrls) {
        StringBuilder html = new StringBuilder("<html><body><div class=\"dgControl\">");
        for (String imageUrl : imageUrls) {
            html.append("<div class=\"iusc\" m='{\"murl\":\"").append(imageUrl).append("\"}'>")
                    .append("<img src=\"").append(imageUrl).append("\"></div>");
        }
        html.append("</div></body></html>");
        doReturn(html.toString()).when(pictureService).fetchBingSearchPage(anyString(), anyInt());
    }

    /**
     * 替换"下载 + COS 转存 + 构造记录"这一步：factory 返回 null 表示该张下载失败
     */
    private void stubPreparePicture(Function<Integer, Picture> factory) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            Picture picture = factory.apply(calls.incrementAndGet());
            if (picture == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片下载失败（测试构造）");
            }
            storedUrls.add(picture.getUrl());
            return picture;
        }).when(pictureService).preparePicture(any(), any(), any(), any(), any());
    }

    /**
     * 造一条"已转存 COS、尚未落库"的记录；url 唯一，模拟真实随机 key
     */
    private Picture newPicture() {
        Picture picture = new Picture();
        String key = UUID.randomUUID().toString().replace("-", "");
        picture.setUrl("https://cos.example.com/public/" + uploader.getId() + "/" + key + ".jpg");
        picture.setThumbnailUrl("https://cos.example.com/public/" + uploader.getId() + "/" + key + "_thumb.jpg");
        picture.setName("t28-batch-" + key);
        picture.setPicSize(1024L);
        picture.setPicWidth(32);
        picture.setPicHeight(32);
        picture.setPicScale(1.0D);
        picture.setPicFormat("jpg");
        picture.setUserId(uploader.getId());
        picture.setReviewStatus(1);
        picture.setReviewTime(new Date());
        return picture;
    }

    private PictureUploadByBatchRequest batchRequest(int count) {
        PictureUploadByBatchRequest request = new PictureUploadByBatchRequest();
        request.setSearchText("t28-batch-search");
        request.setCount(count);
        request.setOffset(0);
        request.setNamePrefix("t28-batch-");
        return request;
    }

    private long countStoredPictures() {
        if (storedUrls.isEmpty()) {
            return 0L;
        }
        return pictureServiceForQuery.lambdaQuery().in(Picture::getUrl, storedUrls).count();
    }

    /**
     * MySQL 全局状态里的 INSERT 语句计数（多值 INSERT 计 1 条，逐条 INSERT 计 N 条）
     */
    private long comInsertCount() {
        return jdbcTemplate.queryForObject("SHOW GLOBAL STATUS LIKE 'Com_insert'",
                (rs, rowNum) -> rs.getLong(2));
    }
}
