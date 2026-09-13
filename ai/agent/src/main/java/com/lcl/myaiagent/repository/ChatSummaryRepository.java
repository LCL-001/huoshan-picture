package com.lcl.myaiagent.repository;

import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import com.lcl.myaiagent.mapper.ChatSummaryMapper;
import com.lcl.myaiagent.model.po.ChatSummary;
import org.springframework.stereotype.Component;

@Component
public class ChatSummaryRepository extends CrudRepository<ChatSummaryMapper, ChatSummary> {
}