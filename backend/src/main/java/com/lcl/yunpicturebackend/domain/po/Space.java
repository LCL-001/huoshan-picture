package com.lcl.yunpicturebackend.domain.po;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import java.util.Date;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 空间
 * </p>
 *
 * @author author
 * @since 2026-04-24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("space")
@ApiModel(value="Space对象", description="空间")
public class Space implements Serializable {

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty(value = "空间名称")
    private String spaceName;

    @ApiModelProperty(value = "空间级别：0-普通版 1-专业版 2-旗舰版")
    private Integer spaceLevel;

    @ApiModelProperty(value = "空间类型：0-私有 1-团队")
    private Integer spaceType;

    @ApiModelProperty(value = "空间图片的最大总大小")
    private Long maxSize;

    @ApiModelProperty(value = "空间图片的最大数量")
    private Long maxCount;

    @ApiModelProperty(value = "当前空间下图片的总大小")
    private Long totalSize;

    @ApiModelProperty(value = "当前空间下的图片数量")
    private Long totalCount;

    @ApiModelProperty(value = "创建用户 id")
    private Long userId;

    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    @ApiModelProperty(value = "编辑时间")
    private Date editTime;

    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    @ApiModelProperty(value = "是否删除")
    @TableLogic
    private Integer isDelete;


    private static final long serialVersionUID = 1L;
}
