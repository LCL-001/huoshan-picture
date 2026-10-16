package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

/**
 * AI 扩图维护状态（T64）：只读、无副作用、不含任何用户数据，供前端决定弹窗是否展示维护通知。
 * <p>
 * 口径见 docs/decisions.md 2026-10-16。
 */
@Data
public class VOutPaintingStatusVO {

    /**
     * 功能是否可用：false = 维护态（前端隐藏「生成图片」入口）
     */
    private Boolean available;

    /**
     * 不可用时的维护文案（与抛给调用方的异常文案同源）；可用时为 null
     */
    private String message;
}
