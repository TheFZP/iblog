package com.iblog.module.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iblog.module.entity.User;
import com.iblog.module.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
 * 后台用户服务。
 */
@Service
public class UserService extends ServiceImpl<UserMapper, User> {

    public User getByUsername(String username) {
        return getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username), false);
    }
}
