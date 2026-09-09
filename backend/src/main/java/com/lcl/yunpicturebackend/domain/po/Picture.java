package com.lcl.yunpicturebackend.domain.po;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 图片
 * </p>
 *
 * @author author
 * @since 2026-04-18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("picture")
@ApiModel(value="Picture对象", description="图片")
public class Picture implements Serializable {
    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty(value = "图片 url")
    private String url;

    @ApiModelProperty(value = "缩略图 url")
    private String thumbnailUrl;

    @ApiModelProperty(value = "图片名称")
    private String name;

    @ApiModelProperty(value = "简介")
    private String introduction;

    @ApiModelProperty(value = "分类")
    private String category;

    @ApiModelProperty(value = "标签（JSON 数组）")
    private String tags;

    @ApiModelProperty(value = "图片体积")
    private Long picSize;

    @ApiModelProperty(value = "图片宽度")
    private Integer picWidth;

    @ApiModelProperty(value = "图片高度")
    private Integer picHeight;

    @ApiModelProperty(value = "图片宽高比例")
    private Double picScale;

    @ApiModelProperty(value = "图片格式")
    private String picFormat;

    @ApiModelProperty(value = "图片主色调")
    private String picColor;

    @ApiModelProperty(value = "创建用户 id")
    private Long userId;

    @ApiModelProperty(value = "空间 id")
    private Long spaceId;

    @ApiModelProperty(value = "状态：0-待审核; 1-通过; 2-拒绝")
    private Integer reviewStatus;

    @ApiModelProperty(value = "审核信息")
    private String reviewMessage;

    @ApiModelProperty(value = "审核人 id")
    private Long reviewerId;

    @ApiModelProperty(value = "审核时间")
    private Date reviewTime;

    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    @ApiModelProperty(value = "编辑时间")
    private Date editTime;

    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    @ApiModelProperty(value = "是否删除")
    @TableLogic
    private Integer isDelete;


}
