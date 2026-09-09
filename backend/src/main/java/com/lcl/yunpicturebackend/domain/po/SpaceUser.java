package com.lcl.yunpicturebackend.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import java.util.Date;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 空间用户关联
 * </p>
 *
 * @author author
 * @since 2026-05-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("space_user")
@ApiModel(value="SpaceUser对象", description="空间用户关联")
public class SpaceUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "空间 id")
    private Long spaceId;

    @ApiModelProperty(value = "用户 id")
    private Long userId;

    @ApiModelProperty(value = "空间角色：viewer/editor/admin")
    private String spaceRole;

    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    @ApiModelProperty(value = "更新时间")
    private Date updateTime;


}
