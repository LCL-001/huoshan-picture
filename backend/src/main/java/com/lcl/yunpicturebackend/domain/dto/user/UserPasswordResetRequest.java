package com.lcl.yunpicturebackend.domain.dto.user;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 忘记密码重置请求
 */
@Data
@ApiModel(value = "UserPasswordResetRequest对象", description = "忘记密码重置请求")
public class UserPasswordResetRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "邮箱")
    private String email;

    @ApiModelProperty(value = "邮箱验证码")
    private String emailCode;

    @ApiModelProperty(value = "新密码")
    private String newPassword;
}
