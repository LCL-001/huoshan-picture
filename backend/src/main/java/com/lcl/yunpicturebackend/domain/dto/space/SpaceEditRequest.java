package com.lcl.yunpicturebackend.domain.dto.space;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 空间编辑请求（目前仅给用户使用）
 */
@Data
public class SpaceEditRequest implements Serializable {

    /**
     * 空间id
     */
    private Long id;

    /**
     * 空间名称
     */
    private String spaceName;

    private static final long serialVersionUID = 1L;
}
