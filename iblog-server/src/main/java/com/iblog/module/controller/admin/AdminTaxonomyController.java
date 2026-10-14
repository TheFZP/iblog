package com.iblog.module.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import com.iblog.module.entity.Article;
import com.iblog.module.entity.ArticleTag;
import com.iblog.module.entity.Category;
import com.iblog.module.entity.Tag;
import com.iblog.module.mapper.ArticleTagMapper;
import com.iblog.module.service.ArticleService;
import com.iblog.module.service.CategoryService;
import com.iblog.module.service.TagService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台分类与标签管理。
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "后台-分类与标签")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminTaxonomyController {

    private final CategoryService categoryService;
    private final TagService tagService;
    private final ArticleService articleService;
    private final ArticleTagMapper articleTagMapper;

    // ------------------------------------------------------------ 分类

    @Operation(summary = "分类列表")
    @GetMapping("/categories")
    public R<List<Category>> listCategories() {
        return R.ok(categoryService.listWithCount());
    }

    @Operation(summary = "新增分类")
    @PostMapping("/categories")
    public R<Long> createCategory(@RequestBody Category category) {
        if (!StringUtils.hasText(category.getName())) {
            throw new BizException("分类名称不能为空");
        }
        if (!StringUtils.hasText(category.getSlug())) {
            category.setSlug("category-" + System.currentTimeMillis());
        }
        categoryService.checkUnique(category.getSlug(), null);
        category.setId(null);
        categoryService.save(category);
        return R.ok(category.getId());
    }

    @Operation(summary = "编辑分类")
    @PutMapping("/categories")
    public R<Void> updateCategory(@RequestBody Category category) {
        if (category.getId() == null) {
            throw new BizException("缺少分类 ID");
        }
        if (StringUtils.hasText(category.getSlug())) {
            categoryService.checkUnique(category.getSlug(), category.getId());
        }
        categoryService.updateById(category);
        return R.ok();
    }

    @Operation(summary = "删除分类")
    @DeleteMapping("/categories/{id}")
    public R<Void> deleteCategory(@PathVariable Long id) {
        long used = articleService.count(new LambdaQueryWrapper<Article>().eq(Article::getCategoryId, id));
        if (used > 0) {
            throw new BizException("该分类下还有 " + used + " 篇文章，请先调整后再删除");
        }
        categoryService.removeById(id);
        return R.ok();
    }

    // ------------------------------------------------------------ 标签

    @Operation(summary = "标签列表")
    @GetMapping("/tags")
    public R<List<Tag>> listTags() {
        return R.ok(tagService.listWithCount());
    }

    @Operation(summary = "新增标签")
    @PostMapping("/tags")
    public R<Long> createTag(@RequestBody Tag tag) {
        if (!StringUtils.hasText(tag.getName())) {
            throw new BizException("标签名称不能为空");
        }
        if (!StringUtils.hasText(tag.getSlug())) {
            tag.setSlug("tag-" + System.currentTimeMillis());
        }
        tagService.checkUnique(tag.getSlug(), null);
        tag.setId(null);
        tagService.save(tag);
        return R.ok(tag.getId());
    }

    @Operation(summary = "编辑标签")
    @PutMapping("/tags")
    public R<Void> updateTag(@RequestBody Tag tag) {
        if (tag.getId() == null) {
            throw new BizException("缺少标签 ID");
        }
        if (StringUtils.hasText(tag.getSlug())) {
            tagService.checkUnique(tag.getSlug(), tag.getId());
        }
        tagService.updateById(tag);
        return R.ok();
    }

    @Operation(summary = "删除标签")
    @DeleteMapping("/tags/{id}")
    public R<Void> deleteTag(@PathVariable Long id) {
        articleTagMapper.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getTagId, id));
        tagService.removeById(id);
        return R.ok();
    }
}
