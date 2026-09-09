package com.lcl.yunpicturebackend.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("post")
@ApiModel(value = "Post对象", description = "论坛帖子")
public class Post implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "作者 id")
    private Long userId;

    @ApiModelProperty(value = "标题")
    private String title;

    @ApiModelProperty(value = "正文内容")
    private String content;

    @ApiModelProperty(value = "图片列表，逗号分隔")
    private String images;

    @ApiModelProperty(value = "标签，逗号分隔")
    private String tags;

    @ApiModelProperty(value = "点赞数")
    private Integer likeCount;

    @ApiModelProperty(value = "评论数")
    private Integer commentCount;

    @ApiModelProperty(value = "浏览数")
    private Integer viewCount;

    @ApiModelProperty(value = "状态：0-正常 1-隐藏")
    private Integer status;

    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    @TableLogic
    @ApiModelProperty(value = "是否删除")
    private Integer isDelete;

}
