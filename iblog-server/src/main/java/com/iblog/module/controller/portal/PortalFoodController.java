package com.iblog.module.controller.portal;

import com.iblog.common.result.R;
import com.iblog.module.entity.Food;
import com.iblog.module.service.FoodService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 官网前台：美食探店记录（公开访问，只返回已展示的）。
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "官网-美食")
@RestController
@RequestMapping("/api/portal/foods")
@RequiredArgsConstructor
public class PortalFoodController {

    private final FoodService foodService;

    @Operation(summary = "美食列表")
    @GetMapping
    public R<List<Food>> list(@RequestParam(required = false) String cuisine,
                              @RequestParam(required = false) String keyword) {
        return R.ok(foodService.listPublished(cuisine, keyword));
    }

    @Operation(summary = "菜系列表（用于筛选条）")
    @GetMapping("/cuisines")
    public R<List<String>> cuisines() {
        return R.ok(foodService.listCuisines());
    }
}
