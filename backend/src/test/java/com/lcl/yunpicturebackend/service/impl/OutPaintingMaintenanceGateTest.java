package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.api.aliyunai.AliYunAiApi;
import com.lcl.yunpicturebackend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.lcl.yunpicturebackend.domain.dto.picture.CreatePictureOutPaintingTaskRequest;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.VOutPaintingStatusVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 扩图维护门闩（T64）：关闭态必须**先于一切**返回维护提示——不查图、不调百炼、不碰 Redis。
 * <p>
 * 纯 Mockito、无 Spring 上下文：开启态的门闩放行分支只要走到后续校验即可证明"没被拦"，
 * 幂等与配额语义由 {@code OutPaintingTaskIdempotencyIntegrationTest} 在真库上钉住。
 */
@DisplayName("AI 扩图维护门闩（无 Spring）")
class OutPaintingMaintenanceGateTest {

    private static final Long PICTURE_ID = 900000002L;
    private static final Long USER_ID = 900000001L;

    @Mock
    private AliYunAiApi aliYunAiApi;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Spy
    @InjectMocks
    private PictureServiceImpl pictureService;

    private User loginUser;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        loginUser = new User();
        loginUser.setId(USER_ID);
        loginUser.setUserRole("user");
    }

    @Test
    @DisplayName("关闭态创建任务：抛维护提示，且零查图、零百炼调用、零 Redis 触碰")
    void closedGateBlocksCreateTaskBeforeAnythingElse() {
        setGate(false);

        assertThatThrownBy(() -> pictureService.createOutPaintingTask(newRequest(), loginUser))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PictureServiceImpl.OUT_PAINTING_MAINTENANCE_MESSAGE);

        // 门闩在图片存在性校验之前：关闭态不泄露"这张图存在与否"，也不产生任何计费或配额痕迹
        verify(pictureService, never()).getById(any());
        verifyNoInteractions(aliYunAiApi);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("关闭态查询结果：同样抛维护提示，不查归属记录、不调百炼")
    void closedGateBlocksGetTask() {
        setGate(false);

        assertThatThrownBy(() -> pictureService.getOutPaintingTask("any-task-id", loginUser))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PictureServiceImpl.OUT_PAINTING_MAINTENANCE_MESSAGE);

        verifyNoInteractions(aliYunAiApi);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("开启态创建任务：门闩不拦，走到后续校验（图片不存在）")
    void openGateLetsCreateTaskThrough() {
        setGate(true);
        doReturn(null).when(pictureService).getById(PICTURE_ID);

        assertThatThrownBy(() -> pictureService.createOutPaintingTask(newRequest(), loginUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining(PictureServiceImpl.OUT_PAINTING_MAINTENANCE_MESSAGE)
                .hasMessage("图片不存在");
    }

    @Test
    @DisplayName("开启态查询结果：门闩不拦，走到归属校验（归属记录不存在）")
    @SuppressWarnings("unchecked")
    void openGateLetsGetTaskThrough() {
        setGate(true);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);

        assertThatThrownBy(() -> pictureService.getOutPaintingTask("any-task-id", loginUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining(PictureServiceImpl.OUT_PAINTING_MAINTENANCE_MESSAGE)
                .hasMessageContaining("任务不存在");
    }

    @Test
    @DisplayName("维护状态：关闭态 available=false 且带回维护文案，开启态 available=true 且无文案")
    void statusReflectsGate() {
        setGate(false);
        VOutPaintingStatusVO closed = pictureService.getOutPaintingStatus();
        assertThat(closed.getAvailable()).isFalse();
        assertThat(closed.getMessage()).isEqualTo(PictureServiceImpl.OUT_PAINTING_MAINTENANCE_MESSAGE);

        setGate(true);
        VOutPaintingStatusVO open = pictureService.getOutPaintingStatus();
        assertThat(open.getAvailable()).isTrue();
        assertThat(open.getMessage()).isNull();
    }

    private void setGate(boolean enabled) {
        ReflectionTestUtils.setField(pictureService, "outPaintingEnabled", enabled);
    }

    private CreatePictureOutPaintingTaskRequest newRequest() {
        CreatePictureOutPaintingTaskRequest request = new CreatePictureOutPaintingTaskRequest();
        request.setPictureId(PICTURE_ID);
        CreateOutPaintingTaskRequest.Parameters parameters = new CreateOutPaintingTaskRequest.Parameters();
        parameters.setXScale(2f);
        request.setParameters(parameters);
        return request;
    }
}
