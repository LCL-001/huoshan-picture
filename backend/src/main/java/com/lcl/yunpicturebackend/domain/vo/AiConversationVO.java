package com.lcl.yunpicturebackend.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手会话列表项（T36）：索引表 ai_conversation 的直出形态，列表只给 chatId/标题/时间
 */
@Data
@Builder
public class AiConversationVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String chatId;
    private String title;
    private Date createTime;
    private Date updateTime;
}
