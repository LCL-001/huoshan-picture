package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lcl.myaiagent.tools.huoshan.dto.PrepareOperationResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 批量编辑图片·待确认操作（T27.6 确认门，替代原直接写工具 batchEditPictures）：
 * 调图库 POST /ai/assistant/operations/prepare 提交操作计划（后端只存计划，零写库），
 * 成功后返回确认凭据（operationId + digest）与卡片摘要（T27.10 起由后端从既存计划生成，
 * 本工具只透传）；真正的写入只能由用户在确认卡片上确认后由后端原子消费既存计划执行——
 * 模型提交计划不等于用户授权。
 * <p>
 * 参数在本地先做与后端一致的形状校验与归一化（trim、丢空标签），尽早拦住明显不合法的输入；
 * 权限不在这里判——确认执行时由图库按调用者真实身份校验。
 * </p>
 */
public class PrepareBatchEditPicturesTool {

    /** 单次上限：与后端确认门的形状校验一致（MAX_PICTURE_IDS_PER_CALL = 50） */
    static final int MAX_IDS_PER_CALL = 50;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HuoshanApiClient client;
    private final String chatId;

    public PrepareBatchEditPicturesTool(HuoshanApiClient client, String chatId) {
        this.client = client;
        this.chatId = chatId;
    }

    @Tool(description = """
            为"批量修改一个空间里多张图片的分类/标签/名称"生成待确认操作（不会立即改动任何数据）。
            调用前提：
            - 图片 id 必须先用 listPictures 拿到（字符串），原样复制；绝不自己编造、改写或推算 id；
            - 分类与标签取值参考 getTagCategory，优先复用已有标签，词表确实没有才提议新标签；
            - 只有用户明确要求修改时才调用本工具；没说清楚改哪些图、改成什么，就先问清楚。
            返回 JSON：status=pending_confirmation / operationType / operationId / digest / summary / note。
            提交成功只代表"计划已生成、等用户确认"，绝不代表已修改——不要声称已改好，
            也不要重复提交同一操作；用户在确认卡片上确认后由系统执行，结果在确认卡片上展示。
            """)
    public String prepareBatchEditPictures(
            @ToolParam(description = "空间 id（字符串，原样复制 listSpaces 返回的 items[].id）") String spaceId,
            @ToolParam(description = "图片 id 列表（字符串，原样复制 listPictures 返回的 items[].id）") List<String> pictureIdList,
            @ToolParam(description = "分类，可不传；取值见 getTagCategory", required = false) String category,
            @ToolParam(description = "标签列表，可不传；优先复用 getTagCategory 里的已有标签", required = false) List<String> tags,
            @ToolParam(description = "命名规则，可不传；用 {序号} 占位自动编号，例：落日-{序号}", required = false) String nameRule) {
        return HuoshanToolSupport.render(() -> prepare(spaceId, pictureIdList, category, tags, nameRule));
    }

    private PrepareOperationResult prepare(String spaceId, List<String> pictureIdList, String category,
                                           List<String> tags, String nameRule) {
        requireChatId();
        if (spaceId == null || spaceId.isBlank()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少 spaceId：先调 listSpaces 取空间 id");
        }
        List<String> ids = HuoshanToolSupport.cleanList(pictureIdList);
        if (ids.isEmpty()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少 pictureIdList：先调 listPictures 取图片 id");
        }
        if (ids.size() > MAX_IDS_PER_CALL) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "一次最多改 " + MAX_IDS_PER_CALL + " 张，本次给了 " + ids.size() + " 张，请分批调用");
        }
        List<String> cleanTags = HuoshanToolSupport.cleanList(tags);
        String cleanCategory = trimToNull(category);
        String cleanNameRule = trimToNull(nameRule);
        if (cleanCategory == null && cleanTags.isEmpty() && cleanNameRule == null) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "没有要修改的字段：category / tags / nameRule 至少给一个");
        }

        ObjectNode plan = MAPPER.createObjectNode();
        plan.put("operationType", "batch_edit");
        ObjectNode batchEdit = plan.putObject("batchEdit");
        batchEdit.put("spaceId", spaceId);
        ArrayNode idArray = batchEdit.putArray("pictureIdList");
        ids.forEach(idArray::add);
        if (cleanCategory != null) {
            batchEdit.put("category", cleanCategory);
        }
        if (!cleanTags.isEmpty()) {
            ArrayNode tagArray = batchEdit.putArray("tags");
            cleanTags.forEach(tagArray::add);
        }
        if (cleanNameRule != null) {
            batchEdit.put("nameRule", cleanNameRule);
        }
        JsonNode data = client.prepareOperation(chatId, plan);
        return HuoshanToolSupport.pendingConfirmation("batch_edit", data);
    }

    private void requireChatId() {
        if (chatId == null || chatId.isBlank()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少会话标识，无法生成待确认操作：请正常发起对话后再试");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
