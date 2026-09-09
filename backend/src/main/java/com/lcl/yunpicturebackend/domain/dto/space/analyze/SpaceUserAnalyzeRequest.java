package com.lcl.yunpicturebackend.domain.dto.space.analyze;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户空间分析请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class SpaceUserAnalyzeRequest extends SpaceAnalyzeRequest {

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 时间维度：day / week / month
     */
    private String timeDimension;
}
