package com.lcl.yunpicturebackend.domain.dto.user;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 绑定/换绑邮箱请求
 */
@Data
@ApiModel(value = "UserEmailBindRequest对象", description = "绑定邮箱请求")
public class UserEmailBindRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "邮箱")
    private String email;

    @ApiModelProperty(value = "邮箱验证码")
    private String emailCode;
}
