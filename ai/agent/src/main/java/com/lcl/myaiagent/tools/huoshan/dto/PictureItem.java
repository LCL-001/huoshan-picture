package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 图片条目（T7 档 1）：元数据 + 访问 URL，够模型归类、够前端折叠条展示 */
@Data
public class PictureItem {

    /** 图片 id：字符串透传，理由同 {@link SpaceItem#getId()} */
    private String id;

    private String name;

    private String url;

    private String thumbnailUrl;

    private String category;

    private List<String> tags = new ArrayList<>();

    private String picFormat;

    private Long picSize;

    private Integer picWidth;

    private Integer picHeight;

    /** 空间 id：字符串透传，理由同 {@link SpaceItem#getId()} */
    private String spaceId;
}
