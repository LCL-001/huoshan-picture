package com.lcl.yunpicturebackend.domain.vo;

import cn.hutool.core.bean.BeanUtil;
import com.lcl.yunpicturebackend.domain.po.Post;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class PostVO implements Serializable {

    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String images;
    private List<String> tagList;
    private Integer likeCount;
    private Integer commentCount;
    private Integer viewCount;
    private Integer status;
    private Date createTime;
    private Date updateTime;

    private UserVO user;
    private Boolean isLiked;

    private static final long serialVersionUID = 1L;

    public static PostVO objToVo(Post post) {
        if (post == null) return null;
        PostVO vo = new PostVO();
        BeanUtil.copyProperties(post, vo);
        return vo;
    }
}
