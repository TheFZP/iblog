package com.iblog.module.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import com.iblog.module.entity.Food;
import com.iblog.module.service.FoodService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 后台美食栏目管理。
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "后台-美食")
@RestController
@RequestMapping("/api/admin/foods")
@RequiredArgsConstructor
public class AdminFoodController {

    private final FoodService foodService;

    @Operation(summary = "美食列表（含隐藏项）")
    @GetMapping
    public R<List<Food>> list(@RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String cuisine) {
        LambdaQueryWrapper<Food> wrapper = new LambdaQueryWrapper<Food>()
                .orderByAsc(Food::getSort)
                .orderByDesc(Food::getId);
        if (StringUtils.hasText(cuisine)) {
            wrapper.eq(Food::getCuisine, cuisine.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Food::getName, kw)
                    .or().like(Food::getDishes, kw)
                    .or().like(Food::getAddress, kw));
        }
        return R.ok(foodService.list(wrapper));
    }

    @Operation(summary = "新增美食")
    @PostMapping
    public R<Long> create(@RequestBody Food food) {
        validate(food);
        food.setId(null);
        if (food.getStatus() == null) {
            food.setStatus(1);
        }
        if (food.getSort() == null) {
            food.setSort(0);
        }
        foodService.save(food);
        return R.ok(food.getId());
    }

    @Operation(summary = "编辑美食")
    @PutMapping
    public R<Void> update(@RequestBody Food food) {
        if (food.getId() == null) {
            throw new BizException("缺少记录 ID");
        }
        validate(food);
        foodService.updateById(food);
        return R.ok();
    }

    @Operation(summary = "删除美食")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        foodService.removeById(id);
        return R.ok();
    }

    /**
     * 校验：店名必填；经纬度要么都填、要么都不填；星级和人均得在合理区间。
     *
     * <p>经纬度只填一个是常见误操作 —— 存进去官网会拼出非法 URI，
     * 与其等前端报错，不如在这里拦下并说清楚。</p>
     */
    private void validate(Food food) {
        if (!StringUtils.hasText(food.getName())) {
            throw new BizException("店名不能为空");
        }
        food.setName(food.getName().trim());

        boolean hasLng = food.getLongitude() != null;
        boolean hasLat = food.getLatitude() != null;
        if (hasLng != hasLat) {
            throw new BizException("经纬度要一起填：只填一个无法定位");
        }
        if (hasLng && (food.getLongitude().compareTo(BigDecimal.valueOf(-180)) < 0
                || food.getLongitude().compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new BizException("经度应在 -180 ~ 180 之间");
        }
        if (hasLat && (food.getLatitude().compareTo(BigDecimal.valueOf(-90)) < 0
                || food.getLatitude().compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new BizException("纬度应在 -90 ~ 90 之间");
        }
        if (food.getRating() != null && (food.getRating() < 1 || food.getRating() > 5)) {
            throw new BizException("推荐度应在 1 ~ 5 之间");
        }
        if (food.getAvgPrice() != null && food.getAvgPrice() < 0) {
            throw new BizException("人均价格不能为负数");
        }
    }
}
