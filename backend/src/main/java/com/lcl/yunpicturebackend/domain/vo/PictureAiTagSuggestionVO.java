package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 打标的逐张结果：出建议时是"建议内容"，应用时是"已写入的内容"（同一结构两处复用）。
 * <p>
 * {@code ok=false} 表示这张没出结果/没写入，原因在 {@code message}（超时、看图失败、图片地址不可访问…）；
 * 单张失败不影响同一批里的其它张。
 * </p>
 */
@Data
public class PictureAiTagSuggestionVO implements Serializable {

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 看图用的图片地址（回显便于管理员核对是哪张）
     */
    private String url;

    /**
     * 这张是否成功
     */
    private boolean ok;

    /**
     * 建议/已写入的标签
     */
    private List<String> tags = new ArrayList<>();

    /**
     * 建议/已写入的分类（空串表示"模型没给出合适分类"，此字段不会被写入）
     */
    private String category = "";

    /**
     * 结果说明（失败原因，或"仅建议、尚未写入"）
     */
    private String message;

    private static final long serialVersionUID = 1L;
}
