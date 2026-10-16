package com.iblog.module.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.entity.Food;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FoodMapper extends BaseMapper<Food> {
}
