package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 写操作准备结果（T27.5）：confirm 所需的全部信息。
 * operationId 不可猜（SecureRandom）；digest 用于确认卡片展示与回传校验。
 */
@Data
public class AiOperationPrepareVO implements Serializable {

    /** 操作 id（确认凭据的主体：绑定用户与会话、单次消费、短 TTL） */
    private String operationId;

    /** 操作类型（batch_edit / url_import） */
    private String operationType;

    /** 计划指纹（SHA-1 hex）：确认卡片展示并原样回传 */
    private String digest;

    private static final long serialVersionUID = 1L;
}
