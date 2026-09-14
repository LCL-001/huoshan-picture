package com.lcl.myaiagent.tools.huoshan;

import com.lcl.myaiagent.tools.huoshan.dto.BatchEditResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量编辑图片（T7 档 3，**写操作**）：图库 {@code POST /picture/edit/batch} 的落点工具。
 * <p>
 * 粗粒度原则（设计文档 L51/L64）：一次调用改一批，模型一步完成整理，不吃步数预算。
 * 权限不在这里判——图库按调用者真实身份校验（批编要求空间所有者，非所有者回 40101），
 * 这里把图库的失败原样转成结构化错误交给模型。
 * </p>
 * <p>
 * 诚实边界：图库的批编只回一个布尔，且按"空间 + id"过滤——不属于该空间的 id 会被**静默跳过**。
 * 所以返回里报的是 {@code requestedCount}（提交了几张），不是"改成了几张"，并带一句说明；
 * 工具不会替图库承诺逐张成功。
 * </p>
 */
public class BatchEditPicturesTool {

    /** 单次上限：图库侧不设上限，但一次改太多既难核对，失败时也会一步炸掉整轮对话 */
    static final int MAX_IDS_PER_CALL = 50;

    private final HuoshanApiClient client;

    public BatchEditPicturesTool(HuoshanApiClient client) {
        this.client = client;
    }

    @Tool(description = """
            批量修改一个空间里多张图片的分类/标签/名称（写操作，会真实改动用户数据；一期不支持删除）。
            调用前提：
            - 图片 id 必须先用 listPictures 拿到（字符串），原样复制；绝不自己编造、改写或推算 id；
            - 分类与标签取值参考 getTagCategory，优先复用已有标签，词表确实没有才提议新标签；
            - 只有用户明确要求修改时才调用本工具；没说清楚改哪些图、改成什么，就先问清楚。
            返回 JSON：spaceId / requestedCount / appliedFields / success / note。
            注意：图库按"空间 + id"过滤，不属于该空间的 id 会被静默跳过，返回只代表"提交成功"，
            不代表每张都改到了；要确认结果请再用 listPictures 复核。
            """)
    public String batchEditPictures(
            @ToolParam(description = "空间 id（字符串，原样复制 listSpaces 返回的 items[].id）") String spaceId,
            @ToolParam(description = "图片 id 列表（字符串，原样复制 listPictures 返回的 items[].id）") List<String> pictureIdList,
            @ToolParam(description = "分类，可不传；取值见 getTagCategory", required = false) String category,
            @ToolParam(description = "标签列表，可不传；优先复用 getTagCategory 里的已有标签", required = false) List<String> tags,
            @ToolParam(description = "命名规则，可不传；用 {序号} 占位自动编号，例：落日-{序号}", required = false) String nameRule) {
        return HuoshanToolSupport.render(() -> edit(spaceId, pictureIdList, category, tags, nameRule));
    }

    private BatchEditResult edit(String spaceId, List<String> pictureIdList, String category,
                                 List<String> tags, String nameRule) {
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
        List<String> appliedFields = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            appliedFields.add("category");
        }
        if (!cleanTags.isEmpty()) {
            appliedFields.add("tags");
        }
        if (nameRule != null && !nameRule.isBlank()) {
            appliedFields.add("nameRule");
        }
        if (appliedFields.isEmpty()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "没有要修改的字段：category / tags / nameRule 至少给一个");
        }

        boolean success = client.batchEditPictures(spaceId, ids, category, cleanTags, nameRule);

        BatchEditResult result = new BatchEditResult();
        result.setSpaceId(spaceId);
        result.setRequestedCount(ids.size());
        result.setAppliedFields(appliedFields);
        result.setSuccess(success);
        result.setNote("已提交 " + ids.size() + " 张；图库按空间 + id 过滤，不属于该空间的 id 会被静默跳过，"
                + "因此本次只确认提交成功，未逐张确认。");
        return result;
    }
}
