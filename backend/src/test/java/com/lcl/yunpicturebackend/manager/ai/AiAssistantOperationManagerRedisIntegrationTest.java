package com.lcl.yunpicturebackend.manager.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.config.AiAssistantProperties;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationConfirmRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPlan;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPrepareRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiBatchEditPlan;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.AiOperationPrepareVO;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.service.IPictureService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T27.5 确认门的 Redis 原子语义集成验证（真实 Redis + 真实 Lua 脚本）：
 * 存储与消费的原子性、并发确认恰好一次、重放零写入、userId/chatId/digest 不匹配时
 * 拒绝且保留原值、TTL 过期、存储值被篡改时 fail-closed。
 * <p>
 * 需要本机 Redis（localhost:6379），不需要 MySQL（picture service 用 Mockito 替身，
 * 零写入断言即"替身零调用"）；按既有约定以 {@code IntegrationTest} 结尾，不进 CI/门禁，
 * 由本地全量回归执行。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiAssistantOperationManagerRedisIntegrationTest {

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final String CHAT_ID = "chat-it-1";

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private IPictureService pictureService;
    private AiAssistantOperationManager manager;
    /** prepare 创建过的键：测试结束后兜底清理（大多数用例消费即删，不会残留） */
    private final List<String> createdKeys = new ArrayList<>();

    @BeforeAll
    void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @BeforeEach
    void setUpManager() {
        pictureService = mock(IPictureService.class);
        AiAssistantProperties properties = new AiAssistantProperties();
        properties.setOperationTtl(Duration.ofMinutes(10));
        manager = new AiAssistantOperationManager(redisTemplate, new ObjectMapper(), properties, pictureService);
    }

    @AfterAll
    void tearDownRedis() {
        for (String key : createdKeys) {
            redisTemplate.delete(key);
        }
        connectionFactory.destroy();
    }

    private User loginUser(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUserRole("user");
        return user;
    }

    private AiOperationPrepareRequest prepareRequest(String chatId) {
        AiOperationPlan plan = new AiOperationPlan();
        plan.setOperationType(AiOperationPlan.TYPE_BATCH_EDIT);
        AiBatchEditPlan batchEdit = new AiBatchEditPlan();
        batchEdit.setSpaceId(30L);
        batchEdit.setPictureIdList(List.of(1L, 2L));
        batchEdit.setCategory("c1");
        plan.setBatchEdit(batchEdit);
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(chatId);
        request.setPlan(plan);
        return request;
    }

    private AiOperationPrepareVO prepare() {
        AiOperationPrepareVO vo = manager.prepare(USER_ID, prepareRequest(CHAT_ID));
        createdKeys.add(AiAssistantOperationManager.OPERATION_KEY_PREFIX + vo.getOperationId());
        return vo;
    }

    private AiOperationConfirmRequest confirmOf(AiOperationPrepareVO vo) {
        AiOperationConfirmRequest request = new AiOperationConfirmRequest();
        request.setOperationId(vo.getOperationId());
        request.setChatId(CHAT_ID);
        request.setDigest(vo.getDigest());
        return request;
    }

    private String keyOf(AiOperationPrepareVO vo) {
        return AiAssistantOperationManager.OPERATION_KEY_PREFIX + vo.getOperationId();
    }

    @Test
    void prepareThenConfirmExecutesExactlyOnceAndRemovesKey() {
        AiOperationPrepareVO vo = prepare();
        assertThat(redisTemplate.hasKey(keyOf(vo))).isTrue();

        manager.confirm(loginUser(USER_ID), confirmOf(vo));

        assertThat(redisTemplate.hasKey(keyOf(vo))).isFalse();
        Mockito.verify(pictureService).editPictureByBatch(any(), any(User.class));
    }

    @Test
    void replaySecondConfirmFailsWithZeroWrite() {
        AiOperationPrepareVO vo = prepare();
        manager.confirm(loginUser(USER_ID), confirmOf(vo));

        // 重放：operation 已消费，第二次确认零写入（替身零调用）
        assertThatThrownBy(() -> manager.confirm(loginUser(USER_ID), confirmOf(vo)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND_ERROR.getCode());
                    assertThat(e.getMessage()).isEqualTo(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
                });
        Mockito.verify(pictureService, Mockito.times(1)).editPictureByBatch(any(), any(User.class));
    }

    @Test
    void concurrentConfirmsExecuteExactlyOnce() throws Exception {
        AiOperationPrepareVO vo = prepare();
        AtomicInteger executions = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
                    executions.incrementAndGet();
                    return null;
                }).when(pictureService).editPictureByBatch(any(), any(User.class));

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<BusinessException> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                    manager.confirm(loginUser(USER_ID), confirmOf(vo));
                } catch (BusinessException e) {
                    failures.add(e);
                } catch (Exception e) {
                    fail("并发确认出现非业务异常: " + e.getClass().getSimpleName());
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertThat(executions.get()).as("恰好一次执行").isEqualTo(1);
        assertThat(failures).as("其余全部返回统一失败语义").hasSize(threads - 1)
                .allSatisfy(e -> assertThat(e.getMessage())
                        .isEqualTo(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE));
        assertThat(redisTemplate.hasKey(keyOf(vo))).isFalse();
    }

    @Test
    void foreignUserIdIsRejectedAndOperationKept() {
        AiOperationPrepareVO vo = prepare();

        assertThatThrownBy(() -> manager.confirm(loginUser(OTHER_USER_ID), confirmOf(vo)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        Mockito.verifyNoInteractions(pictureService);
        // 拒绝且保留：属主仍可随后正常确认（恰一次语义不被破坏）
        assertThat(redisTemplate.hasKey(keyOf(vo))).isTrue();
        manager.confirm(loginUser(USER_ID), confirmOf(vo));
        Mockito.verify(pictureService, Mockito.times(1)).editPictureByBatch(any(), any(User.class));
    }

    @Test
    void foreignChatIdIsRejectedAndOperationKept() {
        AiOperationPrepareVO vo = prepare();
        AiOperationConfirmRequest foreignChat = confirmOf(vo);
        foreignChat.setChatId("chat-other");

        assertThatThrownBy(() -> manager.confirm(loginUser(USER_ID), foreignChat))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        Mockito.verifyNoInteractions(pictureService);
        assertThat(redisTemplate.hasKey(keyOf(vo))).isTrue();
    }

    @Test
    void tamperedDigestIsRejectedAndOperationKept() {
        AiOperationPrepareVO vo = prepare();
        AiOperationConfirmRequest tampered = confirmOf(vo);
        tampered.setDigest("f".repeat(64));

        assertThatThrownBy(() -> manager.confirm(loginUser(USER_ID), tampered))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        Mockito.verifyNoInteractions(pictureService);
        assertThat(redisTemplate.hasKey(keyOf(vo))).isTrue();
    }

    @Test
    void expiredOperationIsRejectedAfterTtl() {
        AiAssistantProperties properties = new AiAssistantProperties();
        properties.setOperationTtl(Duration.ofSeconds(1));
        AiAssistantOperationManager shortTtlManager = new AiAssistantOperationManager(
                redisTemplate, new ObjectMapper(), properties, pictureService);
        AiOperationPrepareVO vo = shortTtlManager.prepare(USER_ID, prepareRequest(CHAT_ID));
        createdKeys.add(keyOf(vo));

        try {
            Thread.sleep(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("TTL 等待被中断");
        }

        assertThat(redisTemplate.hasKey(keyOf(vo))).isFalse();
        assertThatThrownBy(() -> shortTtlManager.confirm(loginUser(USER_ID), confirmOf(vo)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        Mockito.verifyNoInteractions(pictureService);
    }

    @Test
    void tamperedStoredPlanFailsClosed() {
        // 存储值被外部改动（digest 与计划不再一致）：confirm 拒绝执行，fail-closed
        AiOperationPrepareVO vo = prepare();
        String key = keyOf(vo);
        String planJson = String.valueOf(redisTemplate.opsForHash().get(key, "plan"));
        redisTemplate.opsForHash().put(key, "plan", planJson.replace("c1", "c2"));

        assertThatThrownBy(() -> manager.confirm(loginUser(USER_ID), confirmOf(vo)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        Mockito.verifyNoInteractions(pictureService);
        // 拒绝且未消费：存储值被篡改的操作不允许"消费掉就算了"
        assertThat(redisTemplate.hasKey(key)).isTrue();
    }

    @Test
    void urlImportGoesThroughExistingUploadService() {
        AiOperationPlan plan = new AiOperationPlan();
        plan.setOperationType(AiOperationPlan.TYPE_URL_IMPORT);
        com.lcl.yunpicturebackend.domain.dto.ai.AiUrlImportPlan urlImport =
                new com.lcl.yunpicturebackend.domain.dto.ai.AiUrlImportPlan();
        urlImport.setSpaceId(30L);
        urlImport.setFileUrls(List.of("http://example.com/a.jpg"));
        plan.setUrlImport(urlImport);
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        request.setPlan(plan);
        AiOperationPrepareVO vo = manager.prepare(USER_ID, request);
        createdKeys.add(keyOf(vo));
        when(pictureService.uploadPicture(anyString(), any(), any(User.class))).thenReturn(new PictureVO());

        manager.confirm(loginUser(USER_ID), confirmOf(vo));

        Mockito.verify(pictureService).uploadPicture(anyString(), any(), any(User.class));
    }
}
