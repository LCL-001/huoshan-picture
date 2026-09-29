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
 * 按 URL 导入·待确认操作（T27.6 确认门，替代原直接写工具 batchUploadByUrl）：
 * 调图库 POST /ai/assistant/operations/prepare 提交导入计划（后端只存计划，零写库），
 * 成功后返回确认凭据（operationId + digest）与卡片摘要；真正的下载导入只能由用户在确认卡片上
 * 确认后由后端逐条执行——模型提交计划不等于用户授权。
 * <p>
 * URL 在本地先做与后端一致的形状校验（http/https、条数上限），保证卡片摘要与
 * 后端存储的计划一致；协议/魔数/大小/判重等安全校验与额度全部留在确认执行时的图库 service。
 * </p>
 */
public class PrepareUrlImportPicturesTool {

    /** 单次上限：与后端确认门的形状校验一致（MAX_URLS_PER_CALL = 20） */
    static final int MAX_URLS_PER_CALL = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HuoshanApiClient client;
    private final String chatId;

    public PrepareUrlImportPicturesTool(HuoshanApiClient client, String chatId) {
        this.client = client;
        this.chatId = chatId;
    }

    @Tool(description = """
            为"把一批网络图片直链导入指定空间"生成待确认操作（不会立即下载或导入任何图片）。
            调用前提：
            - URL 只能来自 searchImage 结果或用户消息里的原文，原样复制；绝不自己编造或改写 URL；
            - 只支持 http/https 图片直链；
            - 只有用户明确要求把图片放进某个空间时才调用本工具；没说清楚放哪些图、放进哪个空间，就先问清楚。
            返回 JSON：status=pending_confirmation / operationType / operationId / digest / summary / note。
            提交成功只代表"计划已生成、等用户确认"，绝不代表图片已导入——不要声称已导入，
            也不要重复提交同一操作；用户在确认卡片上确认后由系统逐条下载并导入，结果在确认卡片上展示。
            """)
    public String prepareUrlImportPictures(
            @ToolParam(description = "目标空间 id（字符串，原样复制 listSpaces 返回的 items[].id）") String spaceId,
            @ToolParam(description = "图片直链列表（http/https，原样复制 searchImage 或用户给的 URL）") List<String> fileUrlList) {
        return HuoshanToolSupport.render(() -> prepare(spaceId, fileUrlList));
    }

    private PrepareOperationResult prepare(String spaceId, List<String> fileUrlList) {
        requireChatId();
        if (spaceId == null || spaceId.isBlank()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少 spaceId：先调 listSpaces 取空间 id");
        }
        List<String> urls = HuoshanToolSupport.cleanList(fileUrlList);
        if (urls.isEmpty()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少 fileUrlList：给出要导入的图片直链（可用 searchImage 搜索候选）");
        }
        if (urls.size() > MAX_URLS_PER_CALL) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "一次最多导入 " + MAX_URLS_PER_CALL + " 条，本次给了 " + urls.size() + " 条，请分批调用");
        }
        for (String url : urls) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                        "仅支持 http/https 图片直链：" + url + " 不是合法直链");
            }
        }

        ObjectNode plan = MAPPER.createObjectNode();
        plan.put("operationType", "url_import");
        ObjectNode urlImport = plan.putObject("urlImport");
        urlImport.put("spaceId", spaceId);
        ArrayNode urlArray = urlImport.putArray("fileUrls");
        urls.forEach(urlArray::add);
        JsonNode data = client.prepareOperation(chatId, plan);

        List<String> summary = List.of(
                "目标空间 id：" + spaceId,
                "图片直链：" + urls.size() + " 条");
        return HuoshanToolSupport.pendingConfirmation("url_import", data, summary);
    }

    private void requireChatId() {
        if (chatId == null || chatId.isBlank()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少会话标识，无法生成待确认操作：请正常发起对话后再试");
        }
    }
}
