package com.lcl.yunpicturebackend.domain.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * 服务端规范化的待执行操作计划（T27.5）：prepare 时形状校验并归一后存 Redis，
 * confirm 时原子消费并按本对象执行。
 * <p>
 * 本类同时是 digest 的规范化序列化载体：digest = SHA-1(ObjectMapper 序列化结果)，
 * 字段顺序由类声明序固定，序列化配置在应用生命周期内不变，因此同一计划 digest 稳定；
 * 选 SHA-1 是因为消费脚本要在 Lua 内用 Redis 内置的 {@code redis.sha1hex} 复核计划与摘要一致。
 */
@Data
public class AiOperationPlan implements Serializable {

    /** 批量编辑图片：执行走既有批量编辑 service（PictureService#editPictureByBatch） */
    public static final String TYPE_BATCH_EDIT = "batch_edit";

    /** 按 URL 入库：执行走既有 URL 上传 service（PictureService#uploadPicture） */
    public static final String TYPE_URL_IMPORT = "url_import";

    /** 操作类型，仅允许 {@link #TYPE_BATCH_EDIT} / {@link #TYPE_URL_IMPORT} */
    private String operationType;

    /** 批量编辑计划（operationType = batch_edit 时必须提供，其余场景必须为空） */
    private AiBatchEditPlan batchEdit;

    /** URL 入库计划（operationType = url_import 时必须提供，其余场景必须为空） */
    private AiUrlImportPlan urlImport;

    private static final long serialVersionUID = 1L;
}
