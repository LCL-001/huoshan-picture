package com.lcl.yunpicturebackend.domain.dto.user;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 邮箱验证码发送请求
 */
@Data
@ApiModel(value = "EmailCaptchaRequest对象", description = "邮箱验证码发送请求")
public class EmailCaptchaRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "使用场景：register（注册）/ reset（找回密码）/ bind（绑定邮箱）")
    private String scene;

    @ApiModelProperty(value = "邮箱")
    private String email;

    @ApiModelProperty(value = "图形验证码凭证")
    private String captchaUuid;

    @ApiModelProperty(value = "图形验证码")
    private String captchaCode;
}
