package com.iblog.module.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iblog.common.exception.BizException;
import com.iblog.module.entity.Article;
import com.iblog.module.entity.Category;
import com.iblog.module.mapper.ArticleMapper;
import com.iblog.module.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类服务。
 */
@Service
@RequiredArgsConstructor
public class CategoryService extends ServiceImpl<CategoryMapper, Category> {

    private final ArticleMapper articleMapper;

    /**
     * 分类列表（按 sort 升序），并统计已发布文章数。
     */
    public List<Category> listWithCount() {
        List<Category> categories = list(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSort)
                .orderByDesc(Category::getId));
        for (Category category : categories) {
            Long count = articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                    .eq(Article::getCategoryId, category.getId())
                    .eq(Article::getStatus, 1));
            category.setArticleCount(count);
        }
        return categories;
    }

    /**
     * 保存前校验名称 / slug 唯一。
     */
    public void checkUnique(String slug, Long excludeId) {
        Long count = count(new LambdaQueryWrapper<Category>()
                .eq(Category::getSlug, slug)
                .ne(excludeId != null, Category::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("分类别名（slug）已存在：" + slug);
        }
    }
}
