package com.iblog.module.controller.admin;

import com.iblog.common.result.R;
import com.iblog.module.service.SiteConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台站点配置。
 */
@Tag(name = "后台-站点配置")
@RestController
@RequestMapping("/api/admin/site")
@RequiredArgsConstructor
public class AdminSiteConfigController {

    private final SiteConfigService siteConfigService;

    @Operation(summary = "获取站点配置")
    @GetMapping("/config")
    public R<Map<String, String>> get() {
        return R.ok(siteConfigService.getAll());
    }

    @Operation(summary = "保存站点配置")
    @PutMapping("/config")
    public R<Void> save(@RequestBody Map<String, String> config) {
        siteConfigService.saveAll(config);
        return R.ok();
    }
}
