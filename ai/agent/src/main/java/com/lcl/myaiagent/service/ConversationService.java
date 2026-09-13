package com.lcl.myaiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.myaiagent.common.PageRequest;
import com.lcl.myaiagent.model.po.Conversation;
import com.lcl.myaiagent.mapper.ConversationMapper;
import com.lcl.myaiagent.model.vo.ConversationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationService {

    public static final String DEFAULT_TITLE = "新对话";

    private final ConversationMapper conversationMapper;
    private final ChatMessageService chatMessageService;

    /**
     * 获取或创建会话（需要用户 ID）
     */
    public Conversation getOrCreate(String conversationId, String userId, String type) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            conversation = new Conversation();
            conversation.setId(conversationId);
            conversation.setUserId(userId);
            conversation.setType(type != null ? type : "manus");
            conversation.setTitle(DEFAULT_TITLE);
            conversationMapper.insert(conversation);
        }
        return conversation;
    }

    /**
     * 分页列出用户会话
     */
    public Page<ConversationVO> listUserConversations(String userId, PageRequest pageRequest) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Conversation::getUserId, userId)
               // 存量 love 类型会话隐藏（恋爱大师方向已废止，数据保留不删）
               .ne(Conversation::getType, "love")
               .orderByDesc(Conversation::getUpdateTime);
        Page<Conversation> page = Page.of(pageRequest.getCurrent(), pageRequest.getPageSize());
        Page<Conversation> conversationPage = conversationMapper.selectPage(page, wrapper);

        Page<ConversationVO> voPage = new Page<>(conversationPage.getCurrent(),
                conversationPage.getSize(), conversationPage.getTotal());
        List<ConversationVO> voList = conversationPage.getRecords().stream()
                .map(conv -> {
                    ConversationVO vo = new ConversationVO();
                    vo.setId(conv.getId());
                    vo.setType(conv.getType());
                    vo.setTitle(conv.getTitle());
                    vo.setCreateTime(conv.getCreateTime());
                    vo.setUpdateTime(conv.getUpdateTime());
                    vo.setMessageCount(chatMessageService.countByConversationId(conv.getId()));
                    return vo;
                })
                .toList();
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 更新会话标题（需校验归属）
     */
    public Conversation updateTitle(String conversationId, String userId, String title) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            return null;
        }
        conversation.setTitle(title);
        conversationMapper.updateById(conversation);
        return conversation;
    }

    /**
     * 删除会话（需校验归属）
     */
    public boolean deleteConversation(String conversationId, String userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            return false;
        }
        conversationMapper.deleteById(conversationId);
        chatMessageService.deleteByConversationId(conversationId);
        return true;
    }

    public boolean belongsToUser(String conversationId, String userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        return conversation != null && conversation.getUserId().equals(userId);
    }

    public boolean updateGeneratedTitleIfDefault(String conversationId, String userId, String title) {
        Conversation update = new Conversation();
        update.setTitle(title);
        return conversationMapper.update(update, new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTitle, DEFAULT_TITLE)) == 1;
    }
}
