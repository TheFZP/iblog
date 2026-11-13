package com.iblog.module.stock.controller;

import com.iblog.common.result.R;
import com.iblog.module.stock.service.MarginTargetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 融资融券标的清单（前端用来在股票代码前显示「融」标签）。
 *
 * <p>只读接口，清单本身由 {@link MarginTargetService} 缓存 12 小时；前端也应缓存，
 * 不必每次进页面都拉一遍。</p>
 */
@RestController
@RequestMapping("/api/stock/margin-targets")
@RequiredArgsConstructor
public class MarginTargetController {

    private final MarginTargetService marginTargetService;

    @GetMapping
    public R<List<String>> list() {
        return R.ok(marginTargetService.codes().stream().sorted().toList());
    }
}