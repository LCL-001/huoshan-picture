package com.lcl.yunpicturebackend.domain.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 助手写操作确认请求（T27.5）：**绝不接受任何可替换的业务 payload**。
 * digest 是用户在确认卡片上看到的那份计划的指纹，回传后被原子校验，
 * 保证"执行内容与确认内容逐字段一致"；业务字段（图片 id、标签、URL 等）
 * 只存在于服务端保存的计划里，confirm 请求给不出、也改不了。
 */
@Data
public class AiOperationConfirmRequest implements Serializable {

    /** prepare 返回的操作 id */
    private String operationId;

    /** 所属会话 id（与 prepare 时一致） */
    private String chatId;

    /** prepare 返回的计划指纹（SHA-1 hex），篡改即确认失败 */
    private String digest;

    private static final long serialVersionUID = 1L;
}
