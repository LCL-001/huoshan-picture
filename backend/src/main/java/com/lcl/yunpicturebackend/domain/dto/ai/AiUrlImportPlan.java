package com.lcl.yunpicturebackend.domain.dto.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 按 URL 入库计划（T27.5）：执行时逐条走既有 URL 上传 service，
 * 协议/魔数/大小等 URL 安全校验与判重全部留在 service，这里只做轻量形状校验。
 */
@Data
public class AiUrlImportPlan implements Serializable {

    /** 目标空间 id（助手入库一期必须指定空间） */
    private Long spaceId;

    /** 图片直链列表（http/https），执行顺序即列表顺序 */
    private List<String> fileUrls;

    private static final long serialVersionUID = 1L;
}
