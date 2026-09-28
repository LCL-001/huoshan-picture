package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.po.AiConversation;
import com.lcl.yunpicturebackend.domain.vo.AiConversationVO;

import java.util.List;

public interface IAiConversationService extends IService<AiConversation> {

    /** chat 建流前调用：索引行不存在则建（标题=首条消息截断 64 字），已存在只刷 updateTime 不覆盖标题 */
    void recordChat(Long userId, String chatId, String firstMessage);

    /** 会话列表（按 updateTime 倒序分页；pageSize 钳制 1..50） */
    List<AiConversationVO> listByUser(Long userId, long page, long pageSize);

    /** 归属校验：索引行不存在或不属于该用户即抛 40400 */
    void requireOwned(Long userId, String chatId);
}
