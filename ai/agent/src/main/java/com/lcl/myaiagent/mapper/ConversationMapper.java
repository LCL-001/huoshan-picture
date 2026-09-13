package com.lcl.myaiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lcl.myaiagent.model.po.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
