package com.lcl.myaiagent.model.vo;

import lombok.Data;
import java.util.Date;

@Data
public class LoginUserVO {
    private String id;
    private String username;
    private String userRole;
    private Date createTime;
    private Date updateTime;
}
