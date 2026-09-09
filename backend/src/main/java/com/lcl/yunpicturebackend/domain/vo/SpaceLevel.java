package com.lcl.yunpicturebackend.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SpaceLevel {

    /**
     * 等级值
     */
    private int value;

    /**
     * 等级名称
     */
    private String text;

    /**
     * 空间图片的最大数量
     */
    private long maxCount;

    /**
     * 空间图片的最大总大小
     */
    private long maxSize;
}
