package com.lcl.myaiagent.model.po;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serial;
import java.time.LocalDateTime;
import java.io.Serializable;
import java.util.Date;
import java.util.Map;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.springframework.ai.chat.messages.MessageType;

/**
 * <p>
 * 聊天消息表
 * </p>
 *
 * @author author
 * @since 2026-05-18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "chat_message", autoResultMap = true)
@Schema(name = "ChatMessage对象", description = "聊天消息表")
public class ChatMessage implements Serializable {

    @Serial
    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "会话ID")
    @TableField("conversation_id")
    private String conversationId;

    @Schema(description = "消息类型")
    @TableField("message_type")
    private MessageType messageType;

    @Schema(description = "消息内容")
    @TableField(value = "content")
    private String content;

    @Schema(description = "元数据")
    @TableField(value = "metadata", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @Schema(description = "创建时间")
    @TableField(value = "`create_time`", fill = FieldFill.INSERT)
    private Date createTime;

    @Schema(description = "更新时间")
    @TableField(value = "`update_time`", fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @Schema(description = "是否删除 0-未删除 1-已删除")
    @TableField("`is_delete`")
    @TableLogic
    private Boolean isDelete;


}
