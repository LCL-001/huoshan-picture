package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 动态词表（T7 档 1）：AI 打标必须先看这份受治理的词表，优先复用已有标签 */
@Data
public class TagCategoryResult {

    private List<String> tagList = new ArrayList<>();

    private List<String> categoryList = new ArrayList<>();
}
