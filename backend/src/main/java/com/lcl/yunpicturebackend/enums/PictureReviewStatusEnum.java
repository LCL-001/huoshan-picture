package com.lcl.yunpicturebackend.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum PictureReviewStatusEnum {
    REVIEWING("待审核", 0),
    PASS("通过", 1),
    REFUSE("拒绝", 2);

    private final String text;
    private final Integer value;

    PictureReviewStatusEnum(String text, Integer value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static PictureReviewStatusEnum getEnumByValue(Integer value) {
        if (ObjUtil.isNull(value)) {
            return null;
        }
        for (PictureReviewStatusEnum anEnum : PictureReviewStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
