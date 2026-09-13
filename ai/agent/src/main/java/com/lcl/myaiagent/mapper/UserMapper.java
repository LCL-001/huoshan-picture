package com.lcl.myaiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lcl.myaiagent.model.po.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
