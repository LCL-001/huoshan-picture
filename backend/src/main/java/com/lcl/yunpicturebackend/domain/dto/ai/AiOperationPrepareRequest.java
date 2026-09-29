package com.lcl.yunpicturebackend.domain.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 助手写操作准备请求（T27.5）：引擎在对话中提出写操作计划后，带用户凭据提交到这里。
 * <p>
 * 只承载"要做什么"的规范化计划，不含任何凭据——执行时用的是 confirm 请求的登录态，
 * prepare 阶段零写库（只做轻量形状校验 + 存 Redis）。
 */
@Data
public class AiOperationPrepareRequest implements Serializable {

    /** 所属会话 id：confirm 时必须原样带回，跨会话的确认一律无效 */
    private String chatId;

    /** 规范化操作计划（服务端只认 operationType 声明的类型与对应子计划） */
    private AiOperationPlan plan;

    private static final long serialVersionUID = 1L;
}
