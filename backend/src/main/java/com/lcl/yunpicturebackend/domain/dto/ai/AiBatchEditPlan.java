package com.lcl.yunpicturebackend.domain.dto.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量编辑图片计划（T27.5）：字段与既有批量编辑入参一一对应，执行时原样映射，
 * 不新增语义；权限与词表校验全部留在既有 service。
 */
@Data
public class AiBatchEditPlan implements Serializable {

    /** 目标空间 id */
    private Long spaceId;

    /** 图片 id 列表 */
    private List<Long> pictureIdList;

    /** 分类（可空） */
    private String category;

    /** 标签（可空） */
    private List<String> tags;

    /** 命名规则（可空） */
    private String nameRule;

    private static final long serialVersionUID = 1L;
}
