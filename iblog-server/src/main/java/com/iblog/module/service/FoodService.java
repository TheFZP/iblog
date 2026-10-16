package com.iblog.module.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iblog.module.entity.Food;
import com.iblog.module.mapper.FoodMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 美食探店记录服务。
 */
@Service
public class FoodService extends ServiceImpl<FoodMapper, Food> {

    /**
     * 官网展示用列表：只取 status=1，按 sort 升序，同序按 id 倒序（新增的排前面）。
     *
     * @param cuisine 菜系筛选，为空表示全部
     * @param keyword 关键词，匹配店名 / 特色菜 / 地址
     */
    public List<Food> listPublished(String cuisine, String keyword) {
        LambdaQueryWrapper<Food> wrapper = new LambdaQueryWrapper<Food>()
                .eq(Food::getStatus, 1)
                .orderByAsc(Food::getSort)
                .orderByDesc(Food::getId);

        if (StringUtils.hasText(cuisine)) {
            wrapper.eq(Food::getCuisine, cuisine.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Food::getName, kw)
                    .or().like(Food::getDishes, kw)
                    .or().like(Food::getAddress, kw)
                    .or().like(Food::getCuisine, kw));
        }
        return list(wrapper);
    }

    /**
     * 已启用的菜系列表，供官网做筛选条。空菜系不返回。
     */
    public List<String> listCuisines() {
        return list(new LambdaQueryWrapper<Food>()
                .select(Food::getCuisine)
                .eq(Food::getStatus, 1)
                .isNotNull(Food::getCuisine))
                .stream()
                .map(Food::getCuisine)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}
