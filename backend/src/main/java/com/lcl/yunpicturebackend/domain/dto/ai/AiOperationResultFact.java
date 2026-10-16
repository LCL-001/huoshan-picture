package com.lcl.yunpicturebackend.domain.dto.ai;

/**
 * 确认执行结果的回灌事实（T60）：把"哪一次操作、什么类型、成了没有、计划长什么样"随
 * 模型面说明一起交给引擎，落进该会话记忆。
 * <p>
 * 为什么要带身份：同一次对话里可以并存多张确认卡片，用户还可能乱序确认——只回灌一句
 * "用户已确认并执行批量编辑：提交 2 张" 时，模型分不清是哪一张卡片的结果，处理失败的那张也无从关联。
 * {@code outcome} 取值见 {@link #OUTCOME_EXECUTED} / {@link #OUTCOME_FAILED}；失败路径没有计划摘要，
 * 只有 operationId 与原因（{@code operationType} 可能为空）。
 */
public record AiOperationResultFact(String content, String operationId, String operationType, String outcome) {

    /** 已确认并执行（成功 / 部分失败都算"执行过"） */
    public static final String OUTCOME_EXECUTED = "executed";

    /** 确认了但未执行（确认门拒绝或执行失败） */
    public static final String OUTCOME_FAILED = "failed";
}
