package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lcl.yunpicturebackend.domain.po.AiConversation;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.mapper.AiConversationMapper;
import com.lcl.yunpicturebackend.service.impl.AiConversationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T36 会话索引 service 单测：纯 Mockito，不依赖 MySQL/Redis，进门禁。
 * MP {@code ServiceImpl} 的 {@code baseMapper} 是 protected 字段，用 ReflectionTestUtils 注入 mock。
 */
class AiConversationServiceTest {

    private AiConversationMapper mapper;
    private AiConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiConversationMapper.class);
        service = new AiConversationServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void recordChatInsertsWithTruncatedTitle() {
        when(mapper.selectOne(any())).thenReturn(null);
        String longMessage = "非".repeat(80);

        service.recordChat(100L, "chat-1", longMessage);

        ArgumentCaptor<AiConversation> captor = ArgumentCaptor.forClass(AiConversation.class);
        verify(mapper).insert(captor.capture());
        AiConversation row = captor.getValue();
        assertThat(row.getTitle()).hasSize(65); // 64 字 + 省略号
        assertThat(row.getChatId()).isEqualTo("chat-1");
        assertThat(row.getUserId()).isEqualTo(100L);
        assertThat(row.getUpdateTime()).isNotNull();
    }

    @Test
    void recordChatKeepsTitleAndBumpsTimeWhenExists() {
        AiConversation existing = new AiConversation();
        existing.setId(7L);
        existing.setTitle("旧标题");
        when(mapper.selectOne(any())).thenReturn(existing);

        service.recordChat(100L, "chat-1", "新消息");

        ArgumentCaptor<AiConversation> captor = ArgumentCaptor.forClass(AiConversation.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
        assertThat(captor.getValue().getTitle()).isNull(); // 不覆盖标题
        assertThat(captor.getValue().getUpdateTime()).isNotNull();
        verify(mapper, never()).insert(any(AiConversation.class));
    }

    @Test
    void recordChatSkipsBlankChatId() {
        service.recordChat(100L, " ", "hi");
        verify(mapper, never()).selectOne(any());
        verify(mapper, never()).insert(any(AiConversation.class));
    }

    @Test
    void recordChatSurvivesConcurrentDuplicate() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AiConversation.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        service.recordChat(100L, "chat-1", "hi"); // 不抛出
    }

    @Test
    void listByUserMapsToVO() {
        AiConversation row = new AiConversation();
        row.setChatId("chat-1");
        row.setTitle("标题");
        row.setCreateTime(new Date(0L));
        row.setUpdateTime(new Date(1L));
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<com.lcl.yunpicturebackend.domain.vo.AiConversationVO> vos =
                service.listByUser(100L, 1, 20);

        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getChatId()).isEqualTo("chat-1");
        assertThat(vos.get(0).getTitle()).isEqualTo("标题");
        // 分页参数被钳制并拼进 LIMIT/OFFSET，无注入面（整数拼接）
        ArgumentCaptor<QueryWrapper<AiConversation>> wrapper =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("LIMIT 20 OFFSET 0");
    }

    @Test
    void requireOwnedThrowsNotFoundWhenAbsent() {
        when(mapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.requireOwned(100L, "chat-x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话不存在或无权访问");
    }

    @Test
    void requireOwnedPassesForOwner() {
        when(mapper.selectCount(any())).thenReturn(1L);
        service.requireOwned(100L, "chat-1"); // 不抛出
    }
}
