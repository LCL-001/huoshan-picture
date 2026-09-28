package com.lcl.yunpicturebackend.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.util.Date;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * AI 助手会话索引（T36）：只记"谁发起过哪些对话"，标题取首条消息截断；
 * 消息体本身仍在引擎 chat_message，本表不冗余
 * </p>
 *
 * @author author
 * @since 2026-09-28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("ai_conversation")
@ApiModel(value = "AiConversation对象", description = "AI 助手会话索引")
public class AiConversation implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "图库用户 id")
    private Long userId;

    @ApiModelProperty(value = "前端对话串标识（crypto.randomUUID，36 字符）")
    private String chatId;

    @ApiModelProperty(value = "会话标题（首条消息截断 64 字，后续不覆盖）")
    private String title;

    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    @ApiModelProperty(value = "是否删除")
    @TableLogic
    private Integer isDelete;
}
