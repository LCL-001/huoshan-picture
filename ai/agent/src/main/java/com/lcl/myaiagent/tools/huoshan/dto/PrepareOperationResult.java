package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.List;

/**
 * 待确认写操作（T27.6 确认门）：prepare 工具的成功返回——模型据此向用户转述，
 * 循环据此把 confirmation 块翻成确认卡片事件（SSE confirmation_required）。
 * 卡片上的 summary 与后端存储的执行计划同源（工具在提交前已归一化）。
 */
@Data
public class PrepareOperationResult {

    /** 固定取值：表示"计划已生成、待用户确认"，尚未发生任何改动 */
    public static final String STATUS_PENDING_CONFIRMATION = "pending_confirmation";

    /** 固定 pending_confirmation：待确认，尚未发生任何改动 */
    private String status;

    /** 操作类型（batch_edit / url_import），与后端确认门口径一致 */
    private String operationType;

    /** 后端生成的操作 id：确认凭据的主体，用户确认时原样回传 */
    private String operationId;

    /** 计划指纹（SHA-1 hex）：确认卡片展示并原样回传，钉死"确认内容 = 执行内容" */
    private String digest;

    /** 确认卡片逐行展示的内容（目标空间 / 数量 / 字段或 URL 概要） */
    private List<String> summary;

    /** 给模型的转述指引：如何向用户说明这是待确认操作 */
    private String note;
}
