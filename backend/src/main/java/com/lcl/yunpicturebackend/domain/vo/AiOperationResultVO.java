package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 写操作确认执行结果摘要（T27.5）：可展示的结构化结果，不含内部异常原文。
 * batch_edit：requestedCount = 提交张数，success = 服务调用是否成功
 * （图库按"空间 + 图片 id"过滤，无法逐张确认，因此不填 succeededCount/failedCount）；
 * url_import：三种计数齐全，items 逐条上报。
 */
@Data
public class AiOperationResultVO implements Serializable {

    /** 操作类型（batch_edit / url_import） */
    private String operationType;

    /** 本次执行是否成功（batch_edit = 服务调用成功；url_import = 全部条目成功） */
    private Boolean success;

    /** 计划涉及的条目数（batch_edit = 图片张数；url_import = URL 条数） */
    private Integer requestedCount;

    /** URL 入库：成功条数 */
    private Integer succeededCount;

    /** URL 入库：未成功条数（含因登录态失效未尝试的条目） */
    private Integer failedCount;

    /** URL 入库逐条结果（batch_edit 为空列表） */
    private List<AiOperationItemResultVO> items = new ArrayList<>();

    /** 面向用户的补充说明（固定文案，不含 URL、标签值等用户内容） */
    private String message;

    private static final long serialVersionUID = 1L;
}
