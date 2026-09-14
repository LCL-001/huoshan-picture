package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量编辑结果（T7 档 3）。
 * <p>
 * 字段刻意**只表达图库真正告诉我们的东西**：图库回的是"批量更新成功"这一个布尔，不报逐张结果，
 * 且按"空间 + id"过滤——不属于该空间的 id 会被静默跳过。因此这里报 {@code requestedCount}
 * （提交了几张）而不是"改成了几张"，并带一句 {@code note} 把这件事讲给模型听，
 * 免得它替图库承诺"这 10 张都改好了"。
 * </p>
 */
@Data
public class BatchEditResult {

    /** 目标空间 id（字符串，原样回显便于模型与用户核对） */
    private String spaceId;

    /** 本次提交的图片数量（≠ 确认更新成功的数量） */
    private int requestedCount;

    /** 实际下发的字段名，如 ["category","tags","nameRule"] */
    private List<String> appliedFields = new ArrayList<>();

    /** 图库返回的批量更新结果 */
    private boolean success;

    /** 给模型/用户的说明（如"图库可能静默跳过不属于该空间的 id"） */
    private String note;
}
