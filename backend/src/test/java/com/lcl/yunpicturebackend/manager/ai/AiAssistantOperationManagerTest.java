package com.lcl.yunpicturebackend.manager.ai;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.config.AiAssistantProperties;
import com.lcl.yunpicturebackend.domain.dto.ai.AiBatchEditPlan;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationConfirmRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPlan;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPrepareRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationResultFact;
import com.lcl.yunpicturebackend.domain.dto.ai.AiUrlImportPlan;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureEditByBatchRequest;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.AiOperationItemResultVO;
import com.lcl.yunpicturebackend.domain.vo.AiOperationPrepareVO;
import com.lcl.yunpicturebackend.domain.vo.AiOperationResultVO;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
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
    private ISpaceService spaceService;
    private AiAssistantOperationManager manager;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        pictureService = mock(IPictureService.class);
        spaceService = mock(ISpaceService.class);
        AiAssistantProperties properties = new AiAssistantProperties();
        properties.setOperationTtl(Duration.ofSeconds(Long.parseLong(TTL_SECONDS)));
        manager = new AiAssistantOperationManager(stringRedisTemplate, objectMapper, properties,
                pictureService, spaceService);
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
                .hasMessageContaining("会话标识");
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

    // ---------- prepare：卡片摘要由后端从既存计划生成（T27.10，引擎只透传） ----------

    @Test
    void prepareBuildsUrlImportSummaryWithSpaceNameAndAllUrls() {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.STORE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        when(spaceService.getById(30L)).thenReturn(spaceWithName("默认空间"));

        AiOperationPrepareVO vo = manager.prepare(USER_ID, prepareOf(urlImportOnlyPlan(30L,
                "http://example.com/a.jpg", "http://example.com/b.jpg", "http://example.com/c.jpg")));

        assertThat(vo.getSummary()).containsExactly(
                "目标空间：默认空间（id：30）",
                "图片直链：3 条",
                "1. http://example.com/a.jpg",
                "2. http://example.com/b.jpg",
                "3. http://example.com/c.jpg");
        verifyNoInteractions(pictureService);
    }

    @Test
    void prepareSummaryTruncatesUrlListBeyondFive() {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.STORE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        when(spaceService.getById(30L)).thenReturn(spaceWithName("默认空间"));

        AiOperationPrepareVO vo = manager.prepare(USER_ID, prepareOf(urlImportOnlyPlan(30L,
                "http://example.com/1.jpg", "http://example.com/2.jpg", "http://example.com/3.jpg",
                "http://example.com/4.jpg", "http://example.com/5.jpg", "http://example.com/6.jpg",
                "http://example.com/7.jpg")));

        assertThat(vo.getSummary()).startsWith(
                "目标空间：默认空间（id：30）",
                "图片直链：7 条",
                "1. http://example.com/1.jpg",
                "2. http://example.com/2.jpg",
                "3. http://example.com/3.jpg",
                "4. http://example.com/4.jpg",
                "5. http://example.com/5.jpg");
        assertThat(vo.getSummary()).hasSize(8).last().isEqualTo("…（其余 2 条略）");
    }

    @Test
    void prepareSummaryFallsBackToSpaceIdWhenSpaceMissing() {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.STORE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        when(spaceService.getById(30L)).thenReturn(null);

        AiOperationPrepareVO vo = manager.prepare(USER_ID,
                prepareOf(urlImportOnlyPlan(30L, "http://example.com/a.jpg")));

        assertThat(vo.getSummary()).first().isEqualTo("目标空间 id：30");
    }

    @Test
    void prepareBuildsBatchEditSummaryWithIdsAndFieldLines() {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.STORE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        when(spaceService.getById(30L)).thenReturn(spaceWithName("默认空间"));

        AiOperationPlan plan = batchEditPlan(30L, "风景");
        plan.getBatchEdit().setTags(List.of("标签A", "标签B"));
        plan.getBatchEdit().setNameRule("落日-{序号}");
        AiOperationPrepareVO vo = manager.prepare(USER_ID, prepareOf(plan));

        assertThat(vo.getSummary()).containsExactly(
                "目标空间：默认空间（id：30）",
                "目标图片：2 张（id：1、2）",
                "分类 → 风景",
                "标签 → 标签A、标签B",
                "命名规则 → 落日-{序号}");
    }

    @Test
    void prepareSummaryTruncatesIdListBeyondFive() {
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.STORE_SCRIPT), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        when(spaceService.getById(30L)).thenReturn(spaceWithName("默认空间"));

        AiOperationPlan plan = batchEditPlan(30L, "风景");
        plan.getBatchEdit().setPictureIdList(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        AiOperationPrepareVO vo = manager.prepare(USER_ID, prepareOf(plan));

        assertThat(vo.getSummary()).contains("目标图片：7 张（id：1、2、3、4、5…其余 2 张略）");
        assertThat(vo.getSummary()).noneSatisfy(line -> assertThat(line).contains("6、7"));
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

    @Test
    void operationResultNoteForBatchEditReportsSubmittedCount() {
        AiOperationResultVO result = resultWithContext(AiOperationPlan.TYPE_BATCH_EDIT, "op-1", 2,
                List.of("目标空间：Smoke（id：30）", "目标图片：2 张（id：1、2）"));

        assertThat(AiAssistantOperationManager.operationResultNote(result))
                .isEqualTo("用户已确认并执行批量编辑：提交 2 张。[操作 op-1]\n"
                        + "目标空间：Smoke（id：30）\n目标图片：2 张（id：1、2）");
    }

    @Test
    void operationResultNoteForUrlImportReportsFullSuccess() {
        AiOperationResultVO result = resultWithContext(AiOperationPlan.TYPE_URL_IMPORT, "op-2", 3,
                List.of("目标空间 id：30", "图片直链：3 条"));
        result.setSucceededCount(3);
        result.setFailedCount(0);

        assertThat(AiAssistantOperationManager.operationResultNote(result))
                .isEqualTo("用户已确认并执行 URL 导入：成功 3 / 3 条。[操作 op-2]\n"
                        + "目标空间 id：30\n图片直链：3 条");
    }

    @Test
    void operationResultNoteForUrlImportReportsPartialFailure() {
        AiOperationResultVO result = resultWithContext(AiOperationPlan.TYPE_URL_IMPORT, "op-3", 3,
                List.of("图片直链：3 条"));
        result.setSucceededCount(1);
        result.setFailedCount(2);

        assertThat(AiAssistantOperationManager.operationResultNote(result))
                .isEqualTo("用户已确认并执行 URL 导入：成功 1 / 3 条。其中 2 条未成功。[操作 op-3]\n图片直链：3 条");
    }

    /** 没有身份信息时退回旧形态（单行既成事实）：调用方漏填不该产出畸形文案 */
    @Test
    void operationResultNoteDegradesWithoutOperationContext() {
        AiOperationResultVO result = new AiOperationResultVO();
        result.setOperationType(AiOperationPlan.TYPE_BATCH_EDIT);
        result.setRequestedCount(2);

        assertThat(AiAssistantOperationManager.operationResultNote(result))
                .isEqualTo("用户已确认并执行批量编辑：提交 2 张。");
    }

    @Test
    void operationResultNoteToleratesNullResult() {
        assertThat(AiAssistantOperationManager.operationResultNote(null)).isNotBlank();
    }

    // ---------- T60：失败事实与身份关联 ----------

    @Test
    void operationFailureNoteCarriesOperationIdAndReason() {
        assertThat(AiAssistantOperationManager.operationFailureNote("op-9", "空间额度不足"))
                .isEqualTo("用户确认的写操作执行失败：空间额度不足。[操作 op-9]");
    }

    @Test
    void operationFailureNoteFallsBackWhenReasonBlank() {
        assertThat(AiAssistantOperationManager.operationFailureNote("op-9", "  "))
                .isEqualTo("用户确认的写操作执行失败：" + AiAssistantOperationManager.EXECUTE_FAILURE_MESSAGE
                        + "。[操作 op-9]");
    }

    /** 失败原因的用户安全口径：业务拒绝本就是面向用户的文案，原样保留；其余内部异常一律收敛 */
    @Test
    void userSafeFailureReasonKeepsBusinessMessageButBuriesInternalDetail() {
        assertThat(AiAssistantOperationManager.userSafeFailureReason(
                new BusinessException(ErrorCode.NOT_FOUND_ERROR, "空间图片数量已达上限")))
                .isEqualTo("空间图片数量已达上限");
        assertThat(AiAssistantOperationManager.userSafeFailureReason(
                new IllegalStateException("jdbc:mysql://10.0.0.1 internal state leaked")))
                .isEqualTo(AiAssistantOperationManager.EXECUTE_FAILURE_MESSAGE);
    }

    /** 结果携带本次操作身份与既存计划摘要（T60）：回灌后的模型上下文里多张卡片才可区分 */
    @Test
    void confirmBatchEditCarriesOperationIdAndPlanSummary() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        when(spaceService.getById(30L)).thenReturn(spaceWithName("Smoke"));

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op-42", CHAT_ID, "d"));

        assertThat(vo.getOperationId()).isEqualTo("op-42");
        assertThat(vo.getSummary()).contains("目标空间：Smoke（id：30）");
        assertThat(vo.getSummary()).anySatisfy(line -> assertThat(line).contains("目标图片：2 张"));
        assertThat(vo.getSummary()).anySatisfy(line -> assertThat(line).contains("分类 → c1"));
    }

    @Test
    void confirmUrlImportCarriesOperationIdAndPlanSummary() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(any(), any(), any())).thenReturn(new PictureVO());
        when(spaceService.getById(30L)).thenReturn(spaceWithName("Smoke"));

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op-43", CHAT_ID, "d"));

        assertThat(vo.getOperationId()).isEqualTo("op-43");
        assertThat(vo.getSummary()).contains("目标空间：Smoke（id：30）", "图片直链：2 条");
    }

    @Test
    void confirmBatchEditKeepsSuccessWhenSummaryLookupFails() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        when(spaceService.getById(30L)).thenThrow(new IllegalStateException("summary lookup unavailable"));

        AiOperationResultVO result = manager.confirm(loginUser(), confirmOf("op-65-edit", CHAT_ID, "d"));

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getOperationId()).isEqualTo("op-65-edit");
        assertThat(result.getRequestedCount()).isEqualTo(2);
        assertThat(result.getSummary()).contains("目标空间 id：30", "分类 → c1");
        assertThat(AiAssistantOperationManager.operationResultNote(result)).startsWith("用户已确认并执行批量编辑");
        verify(pictureService, times(1)).editPictureByBatch(any(), any());
    }

    @Test
    void confirmUrlImportKeepsSuccessWhenSummaryLookupFails() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(any(), any(), any())).thenReturn(new PictureVO());
        when(spaceService.getById(30L)).thenThrow(new IllegalStateException("summary lookup unavailable"));

        AiOperationResultVO result = manager.confirm(loginUser(), confirmOf("op-65-import", CHAT_ID, "d"));

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getSucceededCount()).isEqualTo(2);
        assertThat(result.getFailedCount()).isZero();
        assertThat(result.getSummary()).contains("目标空间 id：30", "图片直链：2 条");
        assertThat(AiAssistantOperationManager.operationResultNote(result)).contains("成功 2 / 2 条");
        verify(pictureService, times(2)).uploadPicture(any(), any(), any());
    }

    @Test
    void confirmUrlImportKeepsPartialResultWhenSummaryLookupFails() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(any(), any(), any())).thenReturn(new PictureVO())
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "空间额度不足"));
        when(spaceService.getById(30L)).thenThrow(new IllegalStateException("summary lookup unavailable"));

        AiOperationResultVO result = manager.confirm(loginUser(), confirmOf("op-65-partial", CHAT_ID, "d"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getSucceededCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getSummary()).contains("目标空间 id：30");
        assertThat(AiAssistantOperationManager.operationResultNote(result)).contains("成功 1 / 2 条");
        verify(pictureService, times(2)).uploadPicture(any(), any(), any());
    }

    /** T60 的既有连接点：结果 VO 上的 operationId/摘要可直接喂给回灌说明 */
    @Test
    void operationResultNoteUsesIdentityCarriedOnTheResult() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        when(spaceService.getById(30L)).thenReturn(spaceWithName("Smoke"));

        AiOperationResultVO vo = manager.confirm(loginUser(), confirmOf("op-44", CHAT_ID, "d"));

        assertThat(AiAssistantOperationManager.operationResultNote(vo))
                .startsWith("用户已确认并执行批量编辑：提交 2 张。[操作 op-44]")
                .contains("目标空间：Smoke（id：30）");
    }

    @Test
    void confirmRejectsUntrustedOperationIdWithoutRecordingFact() {
        stubConsume("");
        List<AiOperationResultFact> facts = new ArrayList<>();

        assertThatThrownBy(() -> manager.confirm(loginUser(),
                confirmOf("not-a-real-operation]\nUSER_SUPPLIED_TEXT", CHAT_ID, "d"), facts::add))
                .isInstanceOf(BusinessException.class);

        assertThat(facts).isEmpty();
        verifyNoInteractions(pictureService, spaceService);
    }

    @Test
    void confirmBlankFieldsNeverRecordFailureFact() {
        List<AiOperationResultFact> facts = new ArrayList<>();

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op", CHAT_ID, ""), facts::add))
                .isInstanceOf(BusinessException.class);

        assertThat(facts).isEmpty();
        verifyNoInteractions(stringRedisTemplate, pictureService, spaceService);
    }

    @Test
    void confirmRedisFailureNeverRecordsUnverifiedFact() {
        RuntimeException unavailable = new IllegalStateException("redis unavailable");
        when(stringRedisTemplate.execute(any(), anyList(), any(), any(), any())).thenThrow(unavailable);
        List<AiOperationResultFact> facts = new ArrayList<>();

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op", CHAT_ID, "d"), facts::add))
                .isSameAs(unavailable);

        assertThat(facts).isEmpty();
        verifyNoInteractions(pictureService, spaceService);
    }

    @Test
    void confirmAuthenticatedExecutionFailureRecordsTrustedFactAndKeepsOriginalError() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        BusinessException denied = new BusinessException(ErrorCode.NO_AUTH_ERROR, "无此空间权限");
        doThrow(denied).when(pictureService).editPictureByBatch(any(), any());
        List<AiOperationResultFact> facts = new ArrayList<>();

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op-66", CHAT_ID, "d"), facts::add))
                .isSameAs(denied);

        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.operationId()).isEqualTo("op-66");
            assertThat(fact.operationType()).isEqualTo(AiOperationPlan.TYPE_BATCH_EDIT);
            assertThat(fact.outcome()).isEqualTo(AiOperationResultFact.OUTCOME_FAILED);
            assertThat(fact.content()).isEqualTo("用户确认的写操作执行失败：无此空间权限。[操作 op-66]");
        });
    }

    @Test
    void confirmFailureRecorderCannotMaskOriginalExecutionError() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        BusinessException denied = new BusinessException(ErrorCode.NO_AUTH_ERROR, "无此空间权限");
        doThrow(denied).when(pictureService).editPictureByBatch(any(), any());
        List<AiOperationResultFact> facts = new ArrayList<>();

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op-66", CHAT_ID, "d"), fact -> {
            facts.add(fact);
            throw new IllegalStateException("engine unavailable");
        })).isSameAs(denied);

        assertThat(facts).hasSize(1);
    }

    @Test
    void confirmUnexpectedExecutionFailureRecordsOnlySafeReason() throws Exception {
        stubConsume(objectMapper.writeValueAsString(batchEditPlan(30L, "c1")));
        doThrow(new IllegalStateException("internal jdbc detail")).when(pictureService)
                .editPictureByBatch(any(), any());
        List<AiOperationResultFact> facts = new ArrayList<>();

        assertThatThrownBy(() -> manager.confirm(loginUser(), confirmOf("op-66", CHAT_ID, "d"), facts::add))
                .isInstanceOf(BusinessException.class).hasMessage(AiAssistantOperationManager.EXECUTE_FAILURE_MESSAGE);

        assertThat(facts).singleElement().satisfies(fact ->
                assertThat(fact.content()).contains(AiAssistantOperationManager.EXECUTE_FAILURE_MESSAGE)
                        .doesNotContain("internal jdbc detail", "未执行"));
    }

    @Test
    void confirmReplayDoesNotRecordFailureAfterSuccessfulExecution() throws Exception {
        String plan = objectMapper.writeValueAsString(batchEditPlan(30L, "c1"));
        when(stringRedisTemplate.execute(eq(AiAssistantOperationManager.CONSUME_SCRIPT), anyList(),
                any(), any(), any())).thenReturn(plan, "");
        List<AiOperationResultFact> facts = new ArrayList<>();
        AiOperationConfirmRequest request = confirmOf("op-66", CHAT_ID, "d");

        assertThat(manager.confirm(loginUser(), request, facts::add).getSuccess()).isTrue();
        assertThatThrownBy(() -> manager.confirm(loginUser(), request, facts::add))
                .isInstanceOf(BusinessException.class);

        assertThat(facts).isEmpty();
        verify(pictureService, times(1)).editPictureByBatch(any(), any());
    }

    @Test
    void confirmPartialImportAndSummaryFallbackDoNotRecordHardFailure() throws Exception {
        stubConsume(objectMapper.writeValueAsString(
                urlImportOnlyPlan(30L, "http://example.com/a.jpg", "http://example.com/b.jpg")));
        when(pictureService.uploadPicture(any(), any(), any())).thenReturn(new PictureVO())
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "空间额度不足"));
        when(spaceService.getById(30L)).thenThrow(new IllegalStateException("summary unavailable"));
        List<AiOperationResultFact> facts = new ArrayList<>();

        AiOperationResultVO result = manager.confirm(loginUser(), confirmOf("op-66", CHAT_ID, "d"), facts::add);

        assertThat(result.getSucceededCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getSummary()).contains("目标空间 id：30");
        assertThat(facts).isEmpty();
    }

    /** T60 的两个操作类型用例共用的结果骨架 */
    private AiOperationResultVO resultWithContext(String operationType, String operationId,
                                                 Integer requestedCount, List<String> summary) {
        AiOperationResultVO result = new AiOperationResultVO();
        result.setOperationType(operationType);
        result.setOperationId(operationId);
        result.setRequestedCount(requestedCount);
        result.setSummary(summary);
        return result;
    }

    private Space spaceWithName(String spaceName) {
        Space space = new Space();
        space.setId(30L);
        space.setSpaceName(spaceName);
        return space;
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
