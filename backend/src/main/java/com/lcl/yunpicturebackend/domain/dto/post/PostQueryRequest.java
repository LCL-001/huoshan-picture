package com.lcl.yunpicturebackend.domain.dto.post;

import com.lcl.yunpicturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class PostQueryRequest extends PageRequest implements Serializable {

    private Long userId;

    private String keyword;

    private static final long serialVersionUID = 1L;
}
