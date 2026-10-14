package com.iblog.module.controller.portal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iblog.common.result.R;
import com.iblog.module.entity.Article;
import com.iblog.module.entity.Category;
import com.iblog.module.entity.Tag;
import com.iblog.module.service.ArticleService;
import com.iblog.module.service.CategoryService;
import com.iblog.module.service.SiteConfigService;
import com.iblog.module.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 官网前台：站点配置、分类、标签、归档、热门文章（公开访问）。
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "官网-公共数据")
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalCommonController {

    private final SiteConfigService siteConfigService;
    private final CategoryService categoryService;
    private final TagService tagService;
    private final ArticleService articleService;

    @Operation(summary = "站点配置")
    @GetMapping("/site")
    public R<Map<String, String>> site() {
        return R.ok(siteConfigService.getAll());
    }

    @Operation(summary = "分类列表")
    @GetMapping("/categories")
    public R<List<Category>> categories() {
        return R.ok(categoryService.listWithCount());
    }

    @Operation(summary = "标签列表")
    @GetMapping("/tags")
    public R<List<Tag>> tags() {
        return R.ok(tagService.listWithCount());
    }

    @Operation(summary = "文章归档（按年月分组）")
    @GetMapping("/archive")
    public R<Map<String, List<Article>>> archive() {
        return R.ok(articleService.archive());
    }

    @Operation(summary = "热门文章（按浏览量）")
    @GetMapping("/hot-articles")
    public R<List<Article>> hotArticles() {
        List<Article> articles = articleService.list(new LambdaQueryWrapper<Article>()
                .select(Article.class, field -> !"content".equals(field.getColumn()))
                .eq(Article::getStatus, 1)
                .orderByDesc(Article::getViewCount)
                .last("limit 8"));
        articleService.fillMeta(articles);
        return R.ok(articles);
    }
}
