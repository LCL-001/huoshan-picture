package com.lcl.yunpicturebackend.domain.vo;

import com.lcl.yunpicturebackend.domain.po.Picture;
import lombok.Data;

import java.util.Date;

/**
 * 回收站列表项（T50）：只给展示必需的字段，不暴露审核等无关信息。
 */
@Data
public class PictureTrashVO {

    private Long id;

    private String name;

    private String url;

    private String thumbnailUrl;

    private Long spaceId;

    private Long picSize;

    /** 进回收站的时间 */
    private Date deleteTime;

    public static PictureTrashVO of(Picture picture) {
        PictureTrashVO vo = new PictureTrashVO();
        vo.setId(picture.getId());
        vo.setName(picture.getName());
        vo.setUrl(picture.getUrl());
        vo.setThumbnailUrl(picture.getThumbnailUrl());
        vo.setSpaceId(picture.getSpaceId());
        vo.setPicSize(picture.getPicSize());
        vo.setDeleteTime(picture.getDeleteTime());
        return vo;
    }
}
