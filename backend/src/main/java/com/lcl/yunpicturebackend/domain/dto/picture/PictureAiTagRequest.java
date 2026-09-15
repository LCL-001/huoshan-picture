package com.lcl.yunpicturebackend.domain.dto.picture;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * AI 打标出建议请求（仅管理员）：给一批图片 id，服务端逐张看图给标签/分类建议（只读，不写库）。
 */
@Data
public class PictureAiTagRequest implements Serializable {

    /**
     * 图片 id 列表（字符串形式的雪花 id 由图库侧 Jackson 转 Long，与既有接口同口径）
     */
    private List<Long> pictureIdList;

    private static final long serialVersionUID = 1L;
}
