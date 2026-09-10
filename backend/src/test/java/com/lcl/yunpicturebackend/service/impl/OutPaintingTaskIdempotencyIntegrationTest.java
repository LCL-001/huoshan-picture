package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.api.aliyunai.AliYunAiApi;
import com.lcl.yunpicturebackend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.lcl.yunpicturebackend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.lcl.yunpicturebackend.domain.dto.picture.CreatePictureOutPaintingTaskRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 扩图任务幂等与配额的集成验证。
 * <p>
 * 只 mock 掉真正花钱的 {@link AliYunAiApi}，幂等键、配额计数、归属记录都走真实 Redis；
 * 用固定的测试用户/图片 id 隔离，避免碰到库里真实用户的数据。
 */
@SpringBootTest(properties = "app.outpainting.daily-quota=1")
class OutPaintingTaskIdempotencyIntegrationTest {

    /**
     * 用不可能与真实数据冲突的 id，且 getById 被 spy 掉，不查库
     */
    private static final long USER_ID = 900000001L;
    private static final long PICTURE_ID = 900000002L;
    private static final String TASK_ID = "p1-4-test-task";

    private static final String IDEMPOTENT_KEY_PREFIX =
            PictureServiceImpl.OUT_PAINTING_IDEMPOTENT_KEY + USER_ID + ":";
    private static final String QUOTA_KEY_PREFIX = PictureServiceImpl.OUT_PAINTING_QUOTA_KEY + USER_ID + ":";

    @SpyBean
    private PictureServiceImpl pictureService;

    @MockBean
    private AliYunAiApi aliYunAiApi;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void stubPicture() {
        Picture picture = new Picture();
        picture.setId(PICTURE_ID);
        picture.setUserId(USER_ID);
        picture.setSpaceId(null);
        picture.setUrl("https://example.com/p1-4-test.png");
        doReturn(picture).when(pictureService).getById(PICTURE_ID);
    }

    @AfterEach
    void cleanUpRedisKeys() {
        deleteByPattern(IDEMPOTENT_KEY_PREFIX + "*");
        deleteByPattern(QUOTA_KEY_PREFIX + "*");
        stringRedisTemplate.delete(PictureServiceImpl.OUT_PAINTING_OWNER_KEY + TASK_ID);
    }

    /**
     * 同参数重复提交只调一次付费接口，第二次直接复用已有 taskId，且不重复占用配额
     */
    @Test
    void duplicateSubmitShouldReuseTaskIdAndCallAiOnce() {
        when(aliYunAiApi.createOutPaintingTask(any())).thenReturn(taskResponse(TASK_ID));

        CreateOutPaintingTaskResponse first = pictureService.createOutPaintingTask(newRequest(1.5f), loginUser());
        CreateOutPaintingTaskResponse second = pictureService.createOutPaintingTask(newRequest(1.5f), loginUser());

        assertEquals(TASK_ID, first.getOutput().getTaskId());
        assertEquals(TASK_ID, second.getOutput().getTaskId(), "重复提交应复用同一个 taskId");
        verify(aliYunAiApi, times(1)).createOutPaintingTask(any());
        // 幂等命中不消耗额度：当日计数仍是 1
        assertEquals("1", stringRedisTemplate.opsForValue().get(quotaKey()));
    }

    /**
     * 参数不同就是不同任务：配额用尽后要给出明确业务提示，而不是静默失败或继续调用付费接口
     */
    @Test
    void quotaExhaustedShouldFailWithClearMessageAndRollback() {
        when(aliYunAiApi.createOutPaintingTask(any())).thenReturn(taskResponse(TASK_ID));

        pictureService.createOutPaintingTask(newRequest(1.5f), loginUser());
        BusinessException exceeded = assertThrows(BusinessException.class,
                () -> pictureService.createOutPaintingTask(newRequest(2.0f), loginUser()));

        assertTrue(exceeded.getMessage().contains("额度已用完"), "超额应给出明确的额度提示，实际：" + exceeded.getMessage());
        verify(aliYunAiApi, times(1)).createOutPaintingTask(any());
        // 失败的这次要回滚，计数不能虚高
        assertEquals("1", stringRedisTemplate.opsForValue().get(quotaKey()));
        // 幂等占位也要释放，否则用户当天再也提交不了这个参数；
        // 剩下的一条是首个成功任务绑定的幂等记录，值为 taskId
        Set<String> remainingKeys = keysByPattern(IDEMPOTENT_KEY_PREFIX + "*");
        assertEquals(1, remainingKeys.size(), "被拒请求的占位应释放，只保留已创建任务的那份");
        assertEquals(TASK_ID, stringRedisTemplate.opsForValue().get(remainingKeys.iterator().next()));
    }

    /**
     * 创建失败（AI 报错）不能把用户永久挡在"创建中"状态，也不能白扣一次配额
     */
    @Test
    void aiFailureShouldReleasePlaceholderAndQuotaSoRetryWorks() {
        doThrow(new BusinessException(50001, "AI 扩图失败")).when(aliYunAiApi).createOutPaintingTask(any());

        assertThrows(BusinessException.class,
                () -> pictureService.createOutPaintingTask(newRequest(1.5f), loginUser()));
        assertEquals("0", stringRedisTemplate.opsForValue().get(quotaKey()), "失败的创建应回滚额度计数");
        assertEquals(0, countByPattern(IDEMPOTENT_KEY_PREFIX + "*"), "占位应被释放");

        // 释放后同一参数可以正常重试
        doReturn(taskResponse(TASK_ID)).when(aliYunAiApi).createOutPaintingTask(any());
        CreateOutPaintingTaskResponse retried = pictureService.createOutPaintingTask(newRequest(1.5f), loginUser());
        assertEquals(TASK_ID, retried.getOutput().getTaskId());
    }

    private CreatePictureOutPaintingTaskRequest newRequest(float xScale) {
        CreatePictureOutPaintingTaskRequest request = new CreatePictureOutPaintingTaskRequest();
        request.setPictureId(PICTURE_ID);
        CreateOutPaintingTaskRequest.Parameters parameters = new CreateOutPaintingTaskRequest.Parameters();
        parameters.setXScale(xScale);
        parameters.setOutputRatio("1:1");
        request.setParameters(parameters);
        return request;
    }

    private User loginUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUserRole("user");
        return user;
    }

    private CreateOutPaintingTaskResponse taskResponse(String taskId) {
        CreateOutPaintingTaskResponse.Output output = new CreateOutPaintingTaskResponse.Output();
        output.setTaskId(taskId);
        output.setTaskStatus("PENDING");
        CreateOutPaintingTaskResponse response = new CreateOutPaintingTaskResponse();
        response.setOutput(output);
        return response;
    }

    private String quotaKey() {
        return QUOTA_KEY_PREFIX + LocalDate.now();
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = keysByPattern(pattern);
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private Set<String> keysByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        return keys == null ? Collections.emptySet() : keys;
    }

    private int countByPattern(String pattern) {
        return keysByPattern(pattern).size();
    }
}
