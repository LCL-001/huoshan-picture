package com.lcl.yunpicturebackend.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 图形验证码
 */
@Data
@ApiModel(value = "CaptchaVO对象", description = "图形验证码")
public class CaptchaVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "验证码凭证")
    private String captchaUuid;

    @ApiModelProperty(value = "验证码图片（base64 data url，直接用于 <img :src>）")
    private String imageBase64;
}
