package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化分页结果（T7 档 1）：给前端折叠条与模型同一份形状——
 * 先看 total/hasMore 判断要不要继续翻页，再看 items 明细。
 */
@Data
public class PageResult<T> {

    /** 命中总数 */
    private long total;

    /** 当前页号 */
    private long current;

    /** 每页条数 */
    private long pageSize;

    /** 是否还有下一页（前端"继续翻页"与模型"要不要再拉一页"共用一个判断） */
    private boolean hasMore;

    /** 当前页明细 */
    private List<T> items = new ArrayList<>();
}
