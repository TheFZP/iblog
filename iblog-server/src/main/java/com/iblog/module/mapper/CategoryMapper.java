package com.iblog.module.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.entity.Category;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
