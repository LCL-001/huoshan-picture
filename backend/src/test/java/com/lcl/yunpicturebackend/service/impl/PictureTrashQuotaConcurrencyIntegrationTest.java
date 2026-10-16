package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureTrashBatchRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashResultVO;
import com.lcl.yunpicturebackend.enums.SpaceTypeEnum;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.mapper.PictureMapper;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.IUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * 回收站恢复的配额并发安全回归（docs/plan.md T57）。
 * <p>
 * 额度判定原来在 Java 侧读空间快照（`getById`）后决定、写库却是**无条件** `totalCount = totalCount + 1`：
 * 两个并发恢复各自读到同一个快照、一起通过判定，最后把空间加超上限。本类用两种手法钉住修复后的语义：
 * <ol>
 *   <li><b>确定性交错</b>（{@code interleaved*}）：用 {@link SpyBean} 卡在"读完快照、正要回补额度"这一瞬间，
 *       让并发者先占掉最后一个名额再放行——修复前必然越限（红），修复后必须整笔回滚；</li>
 *   <li><b>真并发</b>（{@code concurrent*}）：多线程同时恢复，断言"恰好放行到上限、计数不越限、失败者仍在回收站"，
 *       与线程调度无关。</li>
 * </ol>
 * 真实 MySQL + Redis；直接调 service 层，登录态在工作线程内按 Spring Session + Sa-Token cookie 双凭据绑定
 * （两套登录态的读取都是线程本地的，不能复用主线程的绑定）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("回收站恢复配额并发（真实库）")
class PictureTrashQuotaConcurrencyIntegrationTest {

    private static final String SATOKEN_COOKIE = "satoken";
    private static final long PIC_SIZE = 100L;
    private static final long SIZE_UNLIMITED = 1024L * 1024L;

    @Autowired
    private IPictureService pictureService;
    /** 只为"确定性交错"用例卡点：spy 默认走真实实现，其它用例不受影响 */
    @SpyBean
    private ISpaceService spaceService;
    @Autowired
    private IUserService userService;
    @Autowired
    private PictureMapper pictureMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private Space space;
    private String ownerToken;
    private final List<Long> createdPictureIds = new ArrayList<>();

    @BeforeAll
    void setUpFixtures() {
        owner = new User();
        owner.setUserAccount("trash-quota-owner-" + UUID.randomUUID());
        owner.setUserPassword(userService.getEncryptPassword("password123"));
        owner.setUserName("trash-quota-owner");
        owner.setUserRole(UserConstant.DEFAULT_ROLE);
        userService.save(owner);

        space = new Space();
        space.setSpaceName("trash-quota-space-" + UUID.randomUUID());
        space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        space.setSpaceLevel(0);
        space.setMaxCount(100L);
        space.setMaxSize(SIZE_UNLIMITED);
        space.setTotalCount(0L);
        space.setTotalSize(0L);
        space.setUserId(owner.getId());
        spaceService.save(space);

        // 双登录态：Spring Session 用请求里的 session 属性；Sa-Token 取一个可复放到各工作线程的 token
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
        StpKit.SPACE.login(owner.getId());
        ownerToken = StpKit.SPACE.getTokenValue();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterAll
    void cleanFixtures() {
        // 回收站里的行是 isDelete=1，逻辑删除拦不住，统一裸 SQL 物理清
        createdPictureIds.forEach(id -> jdbcTemplate.update("DELETE FROM picture WHERE id = ?", id));
        if (space != null) {
            jdbcTemplate.update("DELETE FROM space WHERE id = ?", space.getId());
        }
        if (owner != null) {
            userService.removeById(owner.getId());
        }
    }

    // ===== 1. 确定性交错：读完快照后名额被并发者占掉 =====

    @Test
    @DisplayName("交错恢复（数量临界点）：并发者先占掉最后一个名额，本次必须回滚且不越上限")
    void interleavedRestoreRejectedWhenCountSlotTakenConcurrently() throws Exception {
        setSpaceQuota(1L, SIZE_UNLIMITED, 0L, 0L);
        Picture restoring = newTrashedPicture();

        List<PictureTrashResultVO> results =
                interleaveSlotTakenConcurrently(() -> restoreInCurrentThread(restoring.getId()), PIC_SIZE);

        assertThat(results.get(0).isOk()).as("名额已被占掉，本次恢复必须失败").isFalse();
        assertThat(results.get(0).getMessage()).contains("上限");
        assertThat(pictureMapper.selectTrashedById(restoring.getId())).as("失败后记录仍在回收站").isNotNull();
        assertThat(totalCount()).as("不能把计数加到超过上限").isEqualTo(1L);
        assertThat(totalSize()).isEqualTo(PIC_SIZE);
    }

    @Test
    @DisplayName("交错恢复（容量临界点）：并发者先占掉最后一段容量，本次必须回滚且不越上限")
    void interleavedRestoreRejectedWhenSizeSlotTakenConcurrently() throws Exception {
        setSpaceQuota(100L, PIC_SIZE, 0L, 0L);
        Picture restoring = newTrashedPicture();

        List<PictureTrashResultVO> results =
                interleaveSlotTakenConcurrently(() -> restoreInCurrentThread(restoring.getId()), PIC_SIZE);

        assertThat(results.get(0).isOk()).as("容量已被占掉，本次恢复必须失败").isFalse();
        assertThat(results.get(0).getMessage()).contains("上限");
        assertThat(pictureMapper.selectTrashedById(restoring.getId())).as("失败后记录仍在回收站").isNotNull();
        assertThat(totalSize()).as("不能把容量加到超过上限").isEqualTo(PIC_SIZE);
    }

    // ===== 2. 真并发：临界点上恰好放行到上限 =====

    @Test
    @DisplayName("并发恢复（数量临界点）：5 线程抢 2 个名额，恰好成功 2 张且计数不越限")
    void concurrentRestoresCapAtCountLimit() throws Exception {
        setSpaceQuota(2L, SIZE_UNLIMITED, 0L, 0L);
        List<Long> ids = trashedPictureIds(5);

        List<PictureTrashResultVO> results = restoreAllInParallel(ids);

        assertThat(okCount(results)).as("恰好放行到 maxCount=2").isEqualTo(2);
        assertThat(totalCount()).isEqualTo(2L);
        assertThat(totalSize()).isEqualTo(2 * PIC_SIZE);
        assertThat(failedIdsStillInTrash(results)).as("失败者必须留在回收站").hasSize(3);
    }

    @Test
    @DisplayName("并发恢复（容量临界点）：5 线程抢 2 张的容量，恰好成功 2 张且不越限")
    void concurrentRestoresCapAtSizeLimit() throws Exception {
        setSpaceQuota(100L, 2 * PIC_SIZE, 0L, 0L);
        List<Long> ids = trashedPictureIds(5);

        List<PictureTrashResultVO> results = restoreAllInParallel(ids);

        assertThat(okCount(results)).as("恰好放行到 maxSize=2×picSize").isEqualTo(2);
        assertThat(totalSize()).isEqualTo(2 * PIC_SIZE);
        assertThat(totalCount()).isEqualTo(2L);
        assertThat(failedIdsStillInTrash(results)).hasSize(3);
    }

    @Test
    @DisplayName("同图并发恢复：只成功一次、额度只回补一次")
    void concurrentRestoreOfSamePictureRefillsQuotaOnce() throws Exception {
        setSpaceQuota(100L, SIZE_UNLIMITED, 0L, 0L);
        Long id = newTrashedPicture().getId();

        List<PictureTrashResultVO> results = restoreAllInParallel(List.of(id, id, id, id, id));

        assertThat(okCount(results)).as("同一张图只允许恢复一次").isEqualTo(1);
        assertThat(totalCount()).as("额度不能被回补两次").isEqualTo(1L);
        assertThat(totalSize()).isEqualTo(PIC_SIZE);
        assertThat(pictureService.getById(id)).as("恢复后回到图库").isNotNull();
    }

    // ===== 夹具与工具 =====

    /**
     * 在"读完空间快照"与"回补额度"之间插进一个并发者：spy 卡住额度更新的构建，主线程此刻把最后一个名额占掉，
     * 再放行被测请求——修复前它会照着旧快照再加一次（越限），修复后条件在 UPDATE 的 WHERE 上，更新 0 行并回滚。
     */
    private List<PictureTrashResultVO> interleaveSlotTakenConcurrently(Supplier<List<PictureTrashResultVO>> restore,
                                                                      long slotSize) throws Exception {
        CountDownLatch reachedQuotaUpdate = new CountDownLatch(1);
        CountDownLatch slotTaken = new CountDownLatch(1);
        AtomicBoolean slotTakenBeforeResume = new AtomicBoolean(false);
        doAnswer(invocation -> {
            reachedQuotaUpdate.countDown();
            slotTakenBeforeResume.set(slotTaken.await(30, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(spaceService).lambdaUpdate();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<PictureTrashResultVO>> restoring = pool.submit(restore::get);
            assertThat(reachedQuotaUpdate.await(30, TimeUnit.SECONDS)).as("被测请求未走到额度回补").isTrue();
            // 并发者：把最后一个名额占掉（等价于另一个请求先提交）
            jdbcTemplate.update("UPDATE space SET totalCount = totalCount + 1, totalSize = totalSize + ? WHERE id = ?",
                    slotSize, space.getId());
            slotTaken.countDown();

            List<PictureTrashResultVO> results = restoring.get(60, TimeUnit.SECONDS);
            assertThat(slotTakenBeforeResume.get()).as("并发占位未能在被测请求回补前生效，交错未复现").isTrue();
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<PictureTrashResultVO> restoreAllInParallel(List<Long> ids) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(ids.size());
        try {
            CountDownLatch startTogether = new CountDownLatch(1);
            List<Future<List<PictureTrashResultVO>>> futures = ids.stream()
                    .map(id -> pool.submit(() -> {
                        startTogether.await();
                        return restoreInCurrentThread(id);
                    }))
                    .collect(Collectors.toList());
            startTogether.countDown();
            List<PictureTrashResultVO> results = new ArrayList<>();
            for (Future<List<PictureTrashResultVO>> future : futures) {
                results.addAll(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 在本线程绑定双登录态并恢复一张 */
    private List<PictureTrashResultVO> restoreInCurrentThread(Long id) {
        bindOwnerInCurrentThread();
        try {
            return pictureService.restorePictures(batch(id), currentRequest());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private void bindOwnerInCurrentThread() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, owner);
        request.setCookies(new Cookie(SATOKEN_COOKIE, ownerToken));
    }

    private long okCount(List<PictureTrashResultVO> results) {
        return results.stream().filter(PictureTrashResultVO::isOk).count();
    }

    private List<Long> failedIdsStillInTrash(List<PictureTrashResultVO> results) {
        return results.stream()
                .filter(result -> !result.isOk())
                .map(PictureTrashResultVO::getPictureId)
                .filter(id -> pictureMapper.selectTrashedById(id) != null)
                .collect(Collectors.toList());
    }

    private void setSpaceQuota(long maxCount, long maxSize, long totalCount, long totalSize) {
        jdbcTemplate.update("UPDATE space SET maxCount = ?, maxSize = ?, totalCount = ?, totalSize = ? WHERE id = ?",
                maxCount, maxSize, totalCount, totalSize, space.getId());
    }

    private List<Long> trashedPictureIds(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(newTrashedPicture().getId());
        }
        return ids;
    }

    /** 造一条"在回收站里、属于空间"的图片（裸 SQL 置位，绕开被测的写操作） */
    private Picture newTrashedPicture() {
        Picture picture = new Picture();
        picture.setName("trash-quota-" + UUID.randomUUID());
        picture.setUrl("https://mock-cos.test/trash-quota/" + UUID.randomUUID() + ".jpg");
        picture.setPicSize(PIC_SIZE);
        picture.setUserId(owner.getId());
        picture.setSpaceId(space.getId());
        pictureService.save(picture);
        createdPictureIds.add(picture.getId());
        jdbcTemplate.update("UPDATE picture SET isDelete = 1, deleteTime = NOW() WHERE id = ?", picture.getId());
        return picture;
    }

    private PictureTrashBatchRequest batch(Long... ids) {
        PictureTrashBatchRequest request = new PictureTrashBatchRequest();
        request.setPictureIdList(List.of(ids));
        return request;
    }

    private MockHttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return (MockHttpServletRequest) attributes.getRequest();
    }

    private long totalCount() {
        return jdbcTemplate.queryForObject("SELECT totalCount FROM space WHERE id = ?", Long.class, space.getId());
    }

    private long totalSize() {
        return jdbcTemplate.queryForObject("SELECT totalSize FROM space WHERE id = ?", Long.class, space.getId());
    }
}
