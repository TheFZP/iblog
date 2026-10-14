package com.iblog.module.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
