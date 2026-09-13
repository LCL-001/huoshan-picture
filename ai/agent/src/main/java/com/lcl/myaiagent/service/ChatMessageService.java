package com.lcl.myaiagent.service;

import com.lcl.myaiagent.model.po.ChatMessage;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 聊天消息表 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-18
 */
public interface ChatMessageService extends IService<ChatMessage> {

    long countByConversationId(String conversationId);

    void deleteByConversationId(String conversationId);
}
