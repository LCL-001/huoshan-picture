package com.lcl.yunpicturebackend.domain.dto.picture;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * AI 打标应用请求（仅管理员）：把（管理员确认/改过的）逐张建议写进图库。
 * <p>
 * 只写 tags 与 category —— 审核状态与审核人/审核留言一律不动（见 PictureAiTagManager.applyTo）。
 * </p>
 */
@Data
public class PictureAiTagApplyRequest implements Serializable {

    /**
     * 逐张的建议（通常是出建议接口的返回经管理员确认后的形态）
     */
    private List<Item> items;

    /**
     * 单张：要写入的标签与分类；空值表示"这项不改"
     */
    @Data
    public static class Item implements Serializable {

        private Long pictureId;

        private List<String> tags;

        private String category;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}
