package com.iblog.module.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iblog.common.exception.BizException;
import com.iblog.module.entity.Article;
import com.iblog.module.entity.ArticleTag;
import com.iblog.module.entity.Tag;
import com.iblog.module.mapper.ArticleMapper;
import com.iblog.module.mapper.ArticleTagMapper;
import com.iblog.module.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签服务。
 */
@Service
@RequiredArgsConstructor
public class TagService extends ServiceImpl<TagMapper, Tag> {

    private final ArticleTagMapper articleTagMapper;
    private final ArticleMapper articleMapper;

    /**
     * 标签列表并统计已发布文章数。
     */
    public List<Tag> listWithCount() {
        List<Tag> tags = list(new LambdaQueryWrapper<Tag>()
                .orderByAsc(Tag::getSort)
                .orderByDesc(Tag::getId));
        for (Tag tag : tags) {
            tag.setArticleCount(countPublishedArticles(tag.getId()));
        }
        return tags;
    }

    private long countPublishedArticles(Long tagId) {
        List<Long> articleIds = articleTagMapper
                .selectList(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getTagId, tagId))
                .stream().map(ArticleTag::getArticleId).distinct().toList();
        if (articleIds.isEmpty()) {
            return 0L;
        }
        Long count = articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .in(Article::getId, articleIds)
                .eq(Article::getStatus, 1));
        return count == null ? 0L : count;
    }

    public Set<Long> articleIdsByTag(Long tagId) {
        if (tagId == null) {
            return Collections.emptySet();
        }
        return articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getTagId, tagId))
                .stream().map(ArticleTag::getArticleId).collect(Collectors.toSet());
    }

    public void checkUnique(String slug, Long excludeId) {
        Long count = count(new LambdaQueryWrapper<Tag>()
                .eq(Tag::getSlug, slug)
                .ne(excludeId != null, Tag::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("标签别名（slug）已存在：" + slug);
        }
    }
}
