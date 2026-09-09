package com.lcl.yunpicturebackend.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

import static com.lcl.yunpicturebackend.utils.DesensitizeUtils.maskEmail;

/**
 * 用户信息（脱敏）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="LoginUserVO对象", description="用户VO")
public class LoginUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    private Long id;

    @ApiModelProperty(value = "账号")
    private String userAccount;

    @ApiModelProperty(value = "邮箱")
    private String email;

    @ApiModelProperty(value = "用户昵称")
    private String userName;

    @ApiModelProperty(value = "用户头像")
    private String userAvatar;

    @ApiModelProperty(value = "用户简介")
    private String userProfile;

    @ApiModelProperty(value = "用户角色：user/admin")
    private String userRole;

    @ApiModelProperty(value = "编辑时间")
    private Date editTime;

    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /**
     * 邮箱脱敏输出（a***@qq.com）
     */
    public String getEmail() {
        return maskEmail(email);
    }

}
