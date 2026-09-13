package com.lcl.myaiagent.model.vo;

import lombok.Data;
import java.util.Date;

@Data
public class ConversationVO {

    private String id;

    private String type;

    private String title;

    private Date createTime;

    private Date updateTime;

    private long messageCount;
}
