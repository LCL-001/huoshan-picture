package com.lcl.myaiagent.service.impl;

import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.mapper.ChatMessageMapper;
import com.lcl.myaiagent.service.ChatMessageService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 聊天消息表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-18
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    @Override
    public long countByConversationId(String conversationId) {
        return lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .count();
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        lambdaUpdate()
                .eq(ChatMessage::getConversationId, conversationId)
                .remove();
    }
}
