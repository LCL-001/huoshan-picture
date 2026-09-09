package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class PictureTagCategory {
    /**
     * 标签列表
     */
    private List<String> tagList;
    /**
     * 分类列表
     */
    private List<String> categoryList;
}
