package com.iblog.module.controller.admin;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iblog.common.result.R;
import com.iblog.module.entity.VisitLog;
import com.iblog.module.service.VisitLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 后台访问日志：官网（/api/portal/**）访问情况。
 */
@Tag(name = "后台-访问日志")
@RestController
@RequestMapping("/api/admin/visit-logs")
@RequiredArgsConstructor
public class AdminVisitLogController {

    private final VisitLogService visitLogService;

    @Operation(summary = "访问明细分页（时间倒序）")
    @GetMapping
    public R<IPage<VisitLog>> page(@RequestParam(defaultValue = "1") long pageNum,
                                   @RequestParam(defaultValue = "20") long pageSize,
                                   @RequestParam(required = false) String path) {
        return R.ok(visitLogService.pageForAdmin(pageNum, pageSize, path));
    }

    @Operation(summary = "今日统计：PV / 独立 IP / 热门路径 Top10")
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        Map<String, Object> data = new HashMap<>();
        data.put("todayCount", visitLogService.todayCount());
        data.put("todayIpCount", visitLogService.todayIpCount());
        data.put("topPaths", visitLogService.todayTopPaths(10).stream().map(row -> Map.of(
                "path", String.valueOf(row.get("path")),
                "count", row.get("cnt") instanceof Number n ? n.longValue() : 0L
        )).toList());
        return R.ok(data);
    }
}
