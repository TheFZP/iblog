package com.iblog.module.controller.admin;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iblog.common.result.R;
import com.iblog.module.dto.ArticleQueryDTO;
import com.iblog.module.dto.ArticleSaveDTO;
import com.iblog.module.entity.Article;
import com.iblog.module.service.ArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台文章管理。
 */
@Tag(name = "后台-文章管理")
@RestController
@RequestMapping("/api/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    private final ArticleService articleService;

    @Operation(summary = "文章分页列表")
    @GetMapping
    public R<IPage<Article>> page(ArticleQueryDTO query) {
        return R.ok(articleService.pageForAdmin(query));
    }

    @Operation(summary = "文章详情")
    @GetMapping("/{id}")
    public R<Article> detail(@PathVariable Long id) {
        return R.ok(articleService.detail(id, false));
    }

    @Operation(summary = "新增文章")
    @PostMapping
    public R<Long> create(@Valid @RequestBody ArticleSaveDTO dto) {
        dto.setId(null);
        return R.ok(articleService.saveArticle(dto));
    }

    @Operation(summary = "编辑文章")
    @PutMapping
    public R<Void> update(@Valid @RequestBody ArticleSaveDTO dto) {
        articleService.updateArticle(dto);
        return R.ok();
    }

    @Operation(summary = "发布 / 下架")
    @PutMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        articleService.changeStatus(id, status);
        return R.ok();
    }

    @Operation(summary = "删除文章")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        articleService.deleteArticle(id);
        return R.ok();
    }
}
