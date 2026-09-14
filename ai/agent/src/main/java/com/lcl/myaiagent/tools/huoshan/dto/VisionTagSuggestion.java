package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单张图片的打标建议（T7 档 3）：visionTagger 看图后的结果。
 * <p>
 * 只给**建议**、不改数据——落库由 {@code batchEditPictures} 承担，且要等用户确认（设计文档 L58/L62）。
 * </p>
 */
@Data
public class VisionTagSuggestion {

    /** 图片 id（字符串，原样回显，模型拿它去调 batchEditPictures） */
    private String pictureId;

    /** 图片地址（回显便于用户核对看的是哪张） */
    private String url;

    /** 本张是否拿到了可用建议 */
    private boolean ok;

    /** 建议标签（优先来自现有词表；模型认为词表缺词时才给新标签） */
    private List<String> tags = new ArrayList<>();

    /** 建议分类（取不到合适分类时为空字符串） */
    private String category;

    /** 失败原因（看图失败、模型返回无法解析等） */
    private String message;
}
