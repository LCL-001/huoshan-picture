package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.domain.po.AiConversation;
import com.lcl.yunpicturebackend.domain.vo.AiConversationVO;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.mapper.AiConversationMapper;
import com.lcl.yunpicturebackend.service.IAiConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 助手会话索引（T36）：chat 建流前 upsert 一行、按用户出列表、归属校验。
 * 设计口径见 docs/designs/2026-09-28-助手快赢包设计.md（方案 A：索引放 backend）。
 */
@Slf4j
@Service
public class AiConversationServiceImpl extends ServiceImpl<AiConversationMapper, AiConversation>
        implements IAiConversationService {

    private static final int TITLE_MAX_CHARS = 64;
    private static final long MAX_PAGE_SIZE = 50;

    @Override
    public void recordChat(Long userId, String chatId, String firstMessage) {
        if (userId == null || StrUtil.isBlank(chatId)) {
            // chatId 为空 = 引擎自起会话（前端总自带 chatId，这条只兜底），不进索引
            return;
        }
        AiConversation existing = baseMapper.selectOne(new QueryWrapper<AiConversation>()
                .eq("userId", userId).eq("chatId", chatId.trim()));
        Date now = new Date();
        if (existing != null) {
            AiConversation bump = new AiConversation();
            bump.setId(existing.getId());
            bump.setUpdateTime(now); // 显式刷时间，不依赖 on update CURRENT_TIMESTAMP 的空更新语义
            baseMapper.updateById(bump);
            return;
        }
        AiConversation row = new AiConversation();
        row.setUserId(userId);
        row.setChatId(chatId.trim());
        row.setTitle(truncate(firstMessage));
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            baseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发首聊同一 chatId：唯一键兜底即"已存在"，本次不刷时间也无碍
            log.debug("ai_conversation 并发插入冲突（同一 chatId 已由其它请求建立）");
        }
    }

    @Override
    public List<AiConversationVO> listByUser(Long userId, long page, long pageSize) {
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long offset = (Math.max(page, 1) - 1) * size;
        List<AiConversation> rows = baseMapper.selectList(new QueryWrapper<AiConversation>()
                .eq("userId", userId)
                .orderByDesc("updateTime")
                // 整数拼接无注入面；不走分页插件，避免依赖 MP 拦截器配置
                .last("LIMIT " + size + " OFFSET " + offset));
        return rows.stream().map(row -> AiConversationVO.builder()
                        .chatId(row.getChatId())
                        .title(row.getTitle())
                        .createTime(row.getCreateTime())
                        .updateTime(row.getUpdateTime())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void requireOwned(Long userId, String chatId) {
        ThrowUtils.throwIf(StrUtil.isBlank(chatId), ErrorCode.PARAMS_ERROR, "chatId 不能为空");
        Long count = baseMapper.selectCount(new QueryWrapper<AiConversation>()
                .eq("userId", userId).eq("chatId", chatId));
        ThrowUtils.throwIf(count == null || count == 0, ErrorCode.NOT_FOUND_ERROR, "会话不存在或无权访问");
    }

    private String truncate(String message) {
        String trimmed = StrUtil.trimToEmpty(message);
        if (trimmed.isEmpty()) {
            return "新对话";
        }
        return trimmed.length() <= TITLE_MAX_CHARS
                ? trimmed : trimmed.substring(0, TITLE_MAX_CHARS) + "…";
    }
}
