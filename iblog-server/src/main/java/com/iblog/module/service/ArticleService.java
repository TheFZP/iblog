package com.iblog.module.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iblog.common.exception.BizException;
import com.iblog.module.dto.ArticleQueryDTO;
import com.iblog.module.dto.ArticleSaveDTO;
import com.iblog.module.entity.Article;
import com.iblog.module.entity.ArticleTag;
import com.iblog.module.entity.Category;
import com.iblog.module.entity.Tag;
import com.iblog.module.mapper.ArticleMapper;
import com.iblog.module.mapper.ArticleTagMapper;
import com.iblog.module.mapper.CategoryMapper;
import com.iblog.module.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文章服务。
 */
@Service
@RequiredArgsConstructor
public class ArticleService extends ServiceImpl<ArticleMapper, Article> {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ArticleTagMapper articleTagMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final TagService tagService;

    // ---------------------------------------------------------------- 查询

    /**
     * 后台分页查询（不返回正文，减小报文）。
     */
    public IPage<Article> pageForAdmin(ArticleQueryDTO query) {
        return doPage(query, false);
    }

    /**
     * 前台分页查询，只返回已发布文章。
     */
    public IPage<Article> pageForPortal(ArticleQueryDTO query) {
        query.setStatus(1);
        return doPage(query, false);
    }

    private IPage<Article> doPage(ArticleQueryDTO query, boolean withContent) {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        if (!withContent) {
            wrapper.select(Article.class, field -> !"content".equals(field.getColumn()));
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(Article::getTitle, query.getKeyword());
        }
        wrapper.eq(query.getCategoryId() != null, Article::getCategoryId, query.getCategoryId());
        wrapper.eq(query.getStatus() != null, Article::getStatus, query.getStatus());
        wrapper.eq(Boolean.TRUE.equals(query.getOnlyTop()), Article::getIsTop, 1);

        if (query.getTagId() != null) {
            Set<Long> articleIds = tagService.articleIdsByTag(query.getTagId());
            if (articleIds.isEmpty()) {
                return new Page<>(query.getPageNum(), query.getPageSize());
            }
            wrapper.in(Article::getId, articleIds);
        }

        wrapper.orderByDesc(Article::getIsTop)
                .orderByDesc(Article::getPublishTime)
                .orderByDesc(Article::getId);

        Page<Article> page = page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        fillMeta(page.getRecords());
        return page;
    }

    /**
     * 文章详情。
     *
     * @param incrView 是否累加浏览量
     */
    public Article detail(Long id, boolean incrView) {
        Article article = getById(id);
        if (article == null) {
            throw new BizException(404, "文章不存在或已删除");
        }
        if (incrView) {
            baseMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, id)
                    .setSql("view_count = view_count + 1"));
            article.setViewCount((article.getViewCount() == null ? 0 : article.getViewCount()) + 1);
        }
        fillMeta(Collections.singletonList(article));
        fillPrevNext(article);
        return article;
    }

    /**
     * 按年月归档（仅已发布）。
     */
    public Map<String, List<Article>> archive() {
        List<Article> articles = list(new LambdaQueryWrapper<Article>()
                .select(Article.class, field -> !"content".equals(field.getColumn()))
                .eq(Article::getStatus, 1)
                .orderByDesc(Article::getPublishTime));
        fillMeta(articles);

        Map<String, List<Article>> result = new LinkedHashMap<>();
        for (Article article : articles) {
            String key = article.getPublishTime() == null
                    ? "未知时间"
                    : article.getPublishTime().format(MONTH_FORMATTER);
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(article);
        }
        return result;
    }

    // ---------------------------------------------------------------- 写操作

    @Transactional(rollbackFor = Exception.class)
    public Long saveArticle(ArticleSaveDTO dto) {
        Article article = new Article();
        article.setTitle(dto.getTitle());
        article.setSlug(StringUtils.hasText(dto.getSlug()) ? dto.getSlug() : "post-" + System.currentTimeMillis());
        article.setSummary(dto.getSummary());
        article.setContent(dto.getContent());
        article.setCover(dto.getCover());
        article.setCategoryId(dto.getCategoryId());
        article.setAuthor(dto.getAuthor());
        article.setIsTop(dto.getIsTop() == null ? 0 : dto.getIsTop());
        article.setStatus(dto.getStatus() == null ? 0 : dto.getStatus());
        article.setViewCount(0);
        article.setCommentCount(0);
        if (Integer.valueOf(1).equals(article.getStatus())) {
            article.setPublishTime(LocalDateTime.now());
        }
        save(article);
        saveArticleTags(article.getId(), dto.getTagIds());
        return article.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateArticle(ArticleSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("缺少文章 ID");
        }
        Article article = getById(dto.getId());
        if (article == null) {
            throw new BizException(404, "文章不存在或已删除");
        }
        article.setTitle(dto.getTitle());
        if (StringUtils.hasText(dto.getSlug())) {
            article.setSlug(dto.getSlug());
        }
        article.setSummary(dto.getSummary());
        article.setContent(dto.getContent());
        article.setCover(dto.getCover());
        article.setCategoryId(dto.getCategoryId());
        article.setAuthor(dto.getAuthor());
        if (dto.getIsTop() != null) {
            article.setIsTop(dto.getIsTop());
        }
        if (dto.getStatus() != null) {
            // 草稿 -> 发布时补上发布时间
            if (Integer.valueOf(1).equals(dto.getStatus()) && article.getPublishTime() == null) {
                article.setPublishTime(LocalDateTime.now());
            }
            article.setStatus(dto.getStatus());
        }
        updateById(article);

        articleTagMapper.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId, article.getId()));
        saveArticleTags(article.getId(), dto.getTagIds());
    }

    /**
     * 切换发布状态。
     */
    public void changeStatus(Long id, Integer status) {
        Article article = getById(id);
        if (article == null) {
            throw new BizException(404, "文章不存在或已删除");
        }
        LambdaUpdateWrapper<Article> wrapper = new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .set(Article::getStatus, status);
        if (Integer.valueOf(1).equals(status) && article.getPublishTime() == null) {
            wrapper.set(Article::getPublishTime, LocalDateTime.now());
        }
        update(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteArticle(Long id) {
        removeById(id);
        articleTagMapper.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId, id));
    }

    private void saveArticleTags(Long articleId, List<Long> tagIds) {
        if (CollectionUtils.isEmpty(tagIds)) {
            return;
        }
        tagIds.stream().filter(Objects::nonNull).distinct().forEach(tagId -> {
            ArticleTag relation = new ArticleTag();
            relation.setArticleId(articleId);
            relation.setTagId(tagId);
            articleTagMapper.insert(relation);
        });
    }

    // ---------------------------------------------------------------- 数据填充

    /**
     * 批量补充分类名、标签。
     */
    public void fillMeta(List<Article> articles) {
        if (CollectionUtils.isEmpty(articles)) {
            return;
        }
        Set<Long> categoryIds = articles.stream()
                .map(Article::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNameMap = categoryIds.isEmpty() ? Collections.emptyMap()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));

        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        List<ArticleTag> relations = articleTagMapper.selectList(
                new LambdaQueryWrapper<ArticleTag>().in(ArticleTag::getArticleId, articleIds));

        Set<Long> tagIds = relations.stream().map(ArticleTag::getTagId).collect(Collectors.toSet());
        Map<Long, String> tagNameMap = tagIds.isEmpty() ? Collections.emptyMap()
                : tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getName, (a, b) -> a));

        Map<Long, List<Long>> articleTagMap = relations.stream().collect(Collectors.groupingBy(
                ArticleTag::getArticleId, Collectors.mapping(ArticleTag::getTagId, Collectors.toList())));

        for (Article article : articles) {
            article.setCategoryName(categoryNameMap.get(article.getCategoryId()));
            List<Long> ids = articleTagMap.getOrDefault(article.getId(), Collections.emptyList());
            article.setTagIds(ids);
            article.setTagNames(ids.stream().map(tagNameMap::get).filter(Objects::nonNull).toList());
        }
    }

    private void fillPrevNext(Article article) {
        Article prev = getOne(new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getTitle)
                .eq(Article::getStatus, 1)
                .lt(Article::getId, article.getId())
                .orderByDesc(Article::getId)
                .last("limit 1"), false);
        if (prev != null) {
            article.setPrevId(prev.getId());
            article.setPrevTitle(prev.getTitle());
        }

        Article next = getOne(new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getTitle)
                .eq(Article::getStatus, 1)
                .gt(Article::getId, article.getId())
                .orderByAsc(Article::getId)
                .last("limit 1"), false);
        if (next != null) {
            article.setNextId(next.getId());
            article.setNextTitle(next.getTitle());
        }
    }
}
