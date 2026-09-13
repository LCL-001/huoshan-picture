package com.lcl.yunpicturebackend.enums;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

@Getter
public enum TagTypeEnum {

    TAG("标签", "tag"),
    CATEGORY("分类", "category");

    private final String text;

    private final String value;

    TagTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static TagTypeEnum getEnumByValue(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        for (TagTypeEnum tagTypeEnum : TagTypeEnum.values()) {
            if (tagTypeEnum.value.equals(value)) {
                return tagTypeEnum;
            }
        }
        return null;
    }
}
