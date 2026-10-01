package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 写操作准备结果（T27.5）：confirm 所需的全部信息。
 * operationId 不可猜（SecureRandom）；digest 用于确认卡片展示与回传校验。
 * summary 是确认卡片摘要（T27.10）：由后端从既存计划生成（空间名 + 目标清单），
 * 引擎与前端只透传/渲染——卡片内容与执行计划同源，不再由引擎自算。
 */
@Data
public class AiOperationPrepareVO implements Serializable {

    /** 操作 id（确认凭据的主体：绑定用户与会话、单次消费、短 TTL） */
    private String operationId;

    /** 操作类型（batch_edit / url_import） */
    private String operationType;

    /** 计划指纹（SHA-1 hex）：确认卡片展示并原样回传 */
    private String digest;

    /** 确认卡片逐行摘要（T27.10）：空间名 + 前 5 条目标 + 省略；生成见 AiAssistantOperationManager#buildSummary */
    private List<String> summary;

    private static final long serialVersionUID = 1L;
}
