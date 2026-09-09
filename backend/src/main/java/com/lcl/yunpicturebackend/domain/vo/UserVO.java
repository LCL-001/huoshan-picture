package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

import static com.lcl.yunpicturebackend.utils.DesensitizeUtils.maskEmail;

@Data
public class UserVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 账号
     */
    private String userAccount;

    /**
     * 邮箱（脱敏输出）
     */
    private String email;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    private String userRole;

    /**
     * 创建时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;

    /**
     * 邮箱脱敏输出（a***@qq.com）
     */
    public String getEmail() {
        return maskEmail(email);
    }
}
