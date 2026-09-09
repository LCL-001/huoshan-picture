package com.lcl.yunpicturebackend.domain.dto.post;

import lombok.Data;

import java.io.Serializable;

@Data
public class PostEditRequest implements Serializable {

    private Long id;

    private String title;

    private String content;

    private String images;

    private String tags;

    private static final long serialVersionUID = 1L;
}
