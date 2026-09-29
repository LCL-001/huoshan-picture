package com.lcl.yunpicturebackend.manager.ai;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.config.AiAssistantProperties;
import com.lcl.yunpicturebackend.domain.dto.ai.AiBatchEditPlan;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationConfirmRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPlan;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPrepareRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiUrlImportPlan;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureEditByBatchRequest;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.AiOperationItemResultVO;
import com.lcl.yunpicturebackend.domain.vo.AiOperationPrepareVO;
import com.lcl.yunpicturebackend.domain.vo.AiOperationResultVO;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.service.IPictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T27.5 确认门管理器单测：形状校验、digest 规范化、失败语义（统一文案 + 零写入）、
 * 执行内容必须等于存储计划、内部异常不外泄。
 * 纯单测（Mockito），不依赖 MySQL/Redis；Redis Lua 的原子语义（并发恰好一次、重放、TTL、
 * userId/chatId/digest 不匹配时保留原值）由 {@code AiAssistantOperationManagerRedisIntegrationTest}
 * 用真 Redis 覆盖，这里替身"脚本返回计划 / 脚本拒绝返回空串"。
 */
class AiAssistantOperationManagerTest {

    private static final Long USER_ID = 100L;
    private static final String USER_ID_STR = "100";
    private static final String CHAT_ID = "chat-1";
    private static final String TTL_SECONDS = "600";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StringRedisTemplate stringRedisTemplate;
    private IPictureService pictureService;
    private AiAssistantOperationManager manager;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        pictureService = mock(IPictureService.class);
        AiAssistantProperties properties = new AiAssistantProperties();
        properties.setOperationTtl(Duration.ofSeconds(Long.parseLong(TTL_SECONDS)));
        manager = new AiAssistantOperationManager(stringRedisTemplate, objectMapper, properties, pictureService);
    }

    private User loginUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUserRole("user");
        return user;
    }

    // ---------- prepare：形状校验（零 Redis、零 service 交互） ----------

    @Test
    void prepareRejectsBlankChatId() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(" ");
        request.setPlan(batchEditPlan(30L, "c1"));

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chatId");
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsMissingPlan() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsUnknownOperationType() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        AiOperationPlan plan = new AiOperationPlan();
        plan.setOperationType("delete_everything");
        request.setPlan(plan);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsTypeSubPlanMismatch() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        AiOperationPlan plan = batchEditPlan(30L, "c1");
        plan.setUrlImport(urlImportPlan(30L, "http://example.com/a.jpg"));
        request.setPlan(plan);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsBatchEditWithoutSpaceId() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        AiOperationPlan plan = batchEditPlan(30L, "c1");
        plan.getBatchEdit().setSpaceId(null);
        request.setPlan(plan);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsBatchEditWithEmptyPictureIdList() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        AiOperationPlan plan = batchEditPlan(30L, "c1");
        plan.getBatchEdit().setPictureIdList(List.of());
        request.setPlan(plan);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsBatchEditAboveIdCap() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        AiOperationPlan plan = batchEditPlan(30L, "c1");
        Long[] ids = new Long[AiAssistantOperationManager.MAX_PICTURE_IDS_PER_CALL + 1];
        Arrays.fill(ids, 1L);
        plan.getBatchEdit().setPictureIdList(Arrays.asList(ids));
        request.setPlan(plan);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsBatchEditWithoutEditableFields() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        AiOperationPlan plan = batchEditPlan(30L, " ");
        plan.getBatchEdit().setTags(List.of(" "));
        plan.getBatchEdit().setNameRule(" ");
        request.setPlan(plan);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsUrlImportWithoutSpaceId() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        AiOperationPlan plan = urlImportOnlyPlan(30L, "http://example.com/a.jpg");
        plan.getUrlImport().setSpaceId(null);
        request.setPlan(plan);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsUrlImportWithEmptyUrlList() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        AiOperationPlan plan = urlImportOnlyPlan(30L, "http://example.com/a.jpg");
        plan.getUrlImport().setFileUrls(List.of());
        request.setPlan(plan);

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsUrlImportAboveUrlCap() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        String[] urls = new String[AiAssistantOperationManager.MAX_URLS_PER_CALL + 1];
        Arrays.fill(urls, "http://example.com/a.jpg");
        request.setPlan(urlImportOnlyPlan(30L, urls));

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsNonHttpUrl() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        request.setPlan(urlImportOnlyPlan(30L, "ftp://example.com/a.jpg"));

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void prepareRejectsBlankUrl() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        request.setPlan(urlImportOnlyPlan(30L, "   "));

        assertThatThrownBy(() -> manager.prepare(USER_ID, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    // ---------- prepare：存储与 digest（只存计划零写库） ----------

    @Test
    void prepareStoresNormalizedPlanAndReturnsConfirmInfo() throws Exception {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.STORE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);

        AiOperationPlan raw = batchEditPlan(30L, " 分类 ");
        raw.getBatchEdit().setTags(Arrays.asList(" 标签A ", "", "标签B"));
        raw.getBatchEdit().setNameRule(" 落日-{序号} ");
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        request.setPlan(raw);

        AiOperationPrepareVO vo = manager.prepare(USER_ID, request);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> userIdArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> chatIdArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> digestArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> planJsonArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ttlArg = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).execute(eq(AiAssistantOperationManager.STORE_SCRIPT), keysCaptor.capture(),
                userIdArg.capture(), chatIdArg.capture(), digestArg.capture(), planJsonArg.capture(), ttlArg.capture());

        assertThat(vo.getOperationId()).matches("[0-9a-f]{32}");
        assertThat(keysCaptor.getValue())
                .containsExactly(AiAssistantOperationManager.OPERATION_KEY_PREFIX + vo.getOperationId());
        assertThat(userIdArg.getValue()).isEqualTo(USER_ID_STR);
        assertThat(chatIdArg.getValue()).isEqualTo(CHAT_ID);
        assertThat(ttlArg.getValue()).isEqualTo(TTL_SECONDS);

        // 存的是归一化后的计划（trim 过的分类/标签/命名规则），digest = 对存储计划 JSON 的 SHA-256
        AiOperationPlan stored = objectMapper.readValue(planJsonArg.getValue(), AiOperationPlan.class);
        assertThat(stored.getOperationType()).isEqualTo(AiOperationPlan.TYPE_BATCH_EDIT);
        assertThat(stored.getBatchEdit().getSpaceId()).isEqualTo(30L);
        assertThat(stored.getBatchEdit().getPictureIdList()).containsExactly(1L, 2L);
        assertThat(stored.getBatchEdit().getCategory()).isEqualTo("分类");
        assertThat(stored.getBatchEdit().getTags()).containsExactly("标签A", "标签B");
        assertThat(stored.getBatchEdit().getNameRule()).isEqualTo("落日-{序号}");
        assertThat(digestArg.getValue()).isEqualTo(DigestUtil.sha1Hex(planJsonArg.getValue()));
        assertThat(vo.getDigest()).isEqualTo(digestArg.getValue());
        assertThat(vo.getOperationType()).isEqualTo(AiOperationPlan.TYPE_BATCH_EDIT);

        // prepare 只存计划零写库：不触碰 picture service
        verifyNoInteractions(pictureService);
    }

    @Test
    void prepareDigestIsStableForSamePlan() {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.STORE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);

        AiOperationPrepareVO first = manager.prepare(USER_ID, prepareOf(batchEditPlan(30L, "c1")));
        AiOperationPrepareVO second = manager.prepare(USER_ID, prepareOf(batchEditPlan(30L, "c1")));

        assertThat(first.getDigest()).isEqualTo(second.getDigest());
        assertThat(first.getOperationId()).isNotEqualTo(second.getOperationId());
    }

    // ---------- confirm：失败语义（统一文案 + 零写入） ----------

    @Test
    void confirmFailsClosedWhenOperationMissingOrConsumed() {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.CONSUME_SCRIPT), anyList(),
                any(), any(), any())).thenReturn("");

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("missing", CHAT_ID, "d")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND_ERROR.getCode());
                    assertThat(e.getMessage()).isEqualTo(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
                });
        verifyNoInteractions(pictureService);
    }

    @Test
    void confirmFailsClosedOnBlankFields() {
        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("", CHAT_ID, "d")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op", " ", "d")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        verifyNoInteractions(stringRedisTemplate, pictureService);
    }

    @Test
    void confirmFailsClosedOnForeignUserIdOrChatOrDigest() {
        // userId/chatId/digest 任一不匹配时真实脚本拒绝并保留原值——替身按"脚本拒绝"返回空串；
        // 三种拒绝在真实 Redis 下的区分（含原值保留）由集成测试覆盖
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.CONSUME_SCRIPT), anyList(),
                any(), any(), any())).thenReturn("");

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "tampered-digest")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(AiAssistantOperationManager.INVALID_OPERATION_MESSAGE);
        verifyNoInteractions(pictureService);
    }

    // ---------- confirm：执行内容 = 存储计划 ----------

    @Test
    void confirmBatchEditExecutesStoredPlanExactly() throws Exception {
        AiOperationPlan stored = batchEditPlan(30L, "c1");
        stored.getBatchEdit().setTags(List.of("标签A", "标签B"));
        stored.getBatchEdit().setNameRule("落日-{序号}");
        stubConsume(objectMapper.writeValueAsString(stored));

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d"));

        ArgumentCaptor<PictureEditByBatchRequest> requestCaptor = ArgumentCaptor.forClass(PictureEditByBatchRequest.class);
        verify(pictureService).editPictureByBatch(requestCaptor.capture(), any(User.class));
        PictureEditByBatchRequest executed = requestCaptor.getValue();
        assertThat(executed.getSpaceId()).isEqualTo(30L);
        assertThat(executed.getPictureIdList()).containsExactly(1L, 2L);
        assertThat(executed.getCategory()).isEqualTo("c1");
        assertThat(executed.getTags()).containsExactly("标签A", "标签B");
        assertThat(executed.getNameRule()).isEqualTo("落日-{序号}");

        assertThat(vo.getOperationType()).isEqualTo(AiOperationPlan.TYPE_BATCH_EDIT);
        assertThat(vo.getSuccess()).isTrue();
        assertThat(vo.getRequestedCount()).isEqualTo(2);
        assertThat(vo.getItems()).isEmpty();
    }

    @Test
    void confirmBatchEditPassesConfirmLoginUserToService() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        User confirmUser = loginUser();

        manager.confirm(confirmUser, confirmOf("op", CHAT_ID, "d"));

        verify(pictureService).editPictureByBatch(any(), eq(confirmUser));
    }

    @Test
    void confirmBatchEditBusinessFailurePropagatesAsIs() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        doThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR)).when(pictureService).editPictureByBatch(any(), any());

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ErrorCode.NO_AUTH_ERROR.getCode()));
        // 消费后执行失败恰好一次调用：不重试、不产生别的写路径
        verify(pictureService).editPictureByBatch(any(), any());
    }

    @Test
    void confirmBatchEditUnexpectedFailureIsBounded() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        doThrow(new IllegalStateException("jdbc:mysql://10.0.0.1 internal state leaked"))
                .when(pictureService).editPictureByBatch(any(), any());

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
                    assertThat(e.getMessage()).isEqualTo(AiAssistantOperationManager.EXECUTE_FAILURE_MESSAGE);
                });
    }

    @Test
    void confirmMalformedStoredPlanFailsClosed() {
        stubConsume("not-a-json-plan");

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
                    assertThat(e.getMessage()).isEqualTo(AiAssistantOperationManager.EXECUTE_FAILURE_MESSAGE);
                });
        verifyNoInteractions(pictureService);
    }

    // ---------- confirm：URL 入库 ----------

    @Test
    void confirmUrlImportExecutesEachUrlViaExistingService() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(any(), any(), any())).thenReturn(new PictureVO());

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d"));

        InOrder inOrder = inOrder(pictureService);
        inOrder.verify(pictureService).uploadPicture(eq("http://example.com/a.jpg"),
                argThat(req -> req.getSpaceId().equals(30L)), any(User.class));
        inOrder.verify(pictureService).uploadPicture(eq("http://example.com/b.jpg"),
                argThat(req -> req.getSpaceId().equals(30L)), any(User.class));
        assertThat(vo.getOperationType()).isEqualTo(AiOperationPlan.TYPE_URL_IMPORT);
        assertThat(vo.getSuccess()).isTrue();
        assertThat(vo.getRequestedCount()).isEqualTo(2);
        assertThat(vo.getSucceededCount()).isEqualTo(2);
        assertThat(vo.getFailedCount()).isZero();
        assertThat(vo.getItems()).hasSize(2).allSatisfy(item -> assertThat(item.getOk()).isTrue());
    }

    @Test
    void confirmUrlImportReportsPerItemBusinessFailure() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(eq("http://example.com/a.jpg"), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "图片已存在"));
        when(pictureService.uploadPicture(eq("http://example.com/b.jpg"), any(), any()))
                .thenReturn(new PictureVO());

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d"));

        assertThat(vo.getSuccess()).isFalse();
        assertThat(vo.getSucceededCount()).isEqualTo(1);
        assertThat(vo.getFailedCount()).isEqualTo(1);
        AiOperationItemResultVO first = vo.getItems().get(0);
        assertThat(first.getOk()).isFalse();
        assertThat(first.getMessage()).isEqualTo("图片已存在");
        assertThat(vo.getItems().get(1).getOk()).isTrue();
    }

    @Test
    void confirmUrlImportQuotaFailureStaysBoundedPerItem() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "空间条数不足"));

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d"));

        assertThat(vo.getSuccess()).isFalse();
        assertThat(vo.getSucceededCount()).isZero();
        assertThat(vo.getFailedCount()).isEqualTo(2);
        assertThat(vo.getItems()).allSatisfy(item -> {
            assertThat(item.getOk()).isFalse();
            assertThat(item.getMessage()).isEqualTo("空间条数不足");
        });
        verify(pictureService, times(2)).uploadPicture(any(), any(), any());
    }

    @Test
    void confirmUrlImportStopsOnFatalAuthFailure() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d"));

        // 登录态失效：后面的条目必然同样失败，提前停止并如实说明剩余条数
        assertThat(vo.getSuccess()).isFalse();
        assertThat(vo.getSucceededCount()).isZero();
        assertThat(vo.getFailedCount()).isEqualTo(2);
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getMessage()).contains("1");
        verify(pictureService, times(1)).uploadPicture(any(), any(), any());
    }

    @Test
    void confirmUrlImportHidesUnexpectedInternalError() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(eq("http://example.com/a.jpg"), any(), any()))
                .thenThrow(new IllegalStateException("connect refused to 10.0.0.1:9000"));
        when(pictureService.uploadPicture(eq("http://example.com/b.jpg"), any(), any()))
                .thenReturn(new PictureVO());

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d"));

        assertThat(vo.getSucceededCount()).isEqualTo(1);
        assertThat(vo.getFailedCount()).isEqualTo(1);
        String firstMessage = vo.getItems().get(0).getMessage();
        assertThat(firstMessage).isEqualTo("上传失败");
        assertThat(firstMessage).doesNotContain("10.0.0.1");
    }

    // ---------- 工具 ----------

    private void stubConsume(String planJson) {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.CONSUME_SCRIPT), anyList(),
                any(), any(), any())).thenReturn(planJson);
    }

    private AiOperationPrepareRequest prepareOf(AiOperationPlan plan) {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId(CHAT_ID);
        request.setPlan(plan);
        return request;
    }

    private AiOperationConfirmRequest confirmOf(String operationId, String chatId, String digest) {
        AiOperationConfirmRequest request = new AiOperationConfirmRequest();
        request.setOperationId(operationId);
        request.setChatId(chatId);
        request.setDigest(digest);
        return request;
    }

    /** batch_edit 计划（子计划齐全，方便单个字段覆写） */
    private AiOperationPlan batchEditPlan(Long spaceId, String category) {
        AiOperationPlan plan = new AiOperationPlan();
        plan.setOperationType(AiOperationPlan.TYPE_BATCH_EDIT);
        AiBatchEditPlan batchEdit = new AiBatchEditPlan();
        batchEdit.setSpaceId(spaceId);
        batchEdit.setPictureIdList(List.of(1L, 2L));
        batchEdit.setCategory(category);
        plan.setBatchEdit(batchEdit);
        return plan;
    }

    private AiOperationPlan urlImportOnlyPlan(Long spaceId, String... urls) {
        AiOperationPlan plan = new AiOperationPlan();
        plan.setOperationType(AiOperationPlan.TYPE_URL_IMPORT);
        AiUrlImportPlan urlImport = new AiUrlImportPlan();
        urlImport.setSpaceId(spaceId);
        urlImport.setFileUrls(Arrays.asList(urls));
        plan.setUrlImport(urlImport);
        return plan;
    }

    private AiUrlImportPlan urlImportPlan(Long spaceId, String... urls) {
        AiUrlImportPlan urlImport = new AiUrlImportPlan();
        urlImport.setSpaceId(spaceId);
        urlImport.setFileUrls(Arrays.asList(urls));
        return urlImport;
    }
}
