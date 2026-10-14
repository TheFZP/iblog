package com.iblog.module.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iblog.common.exception.BizException;
import com.iblog.module.dto.CommentSubmitDTO;
import com.iblog.module.entity.Article;
import com.iblog.module.entity.Comment;
import com.iblog.module.mapper.ArticleMapper;
import com.iblog.module.mapper.CommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 评论服务。
 */
@Service
@RequiredArgsConstructor
public class CommentService extends ServiceImpl<CommentMapper, Comment> {

    private final ArticleMapper articleMapper;

    /**
     * 后台分页查询全部评论。
     */
    public IPage<Comment> pageForAdmin(long pageNum, long pageSize, Integer status) {
        Page<Comment> page = page(new Page<>(pageNum, pageSize), new LambdaQueryWrapper<Comment>()
                .eq(status != null, Comment::getStatus, status)
                .orderByDesc(Comment::getId));
        fillArticleTitle(page.getRecords());
        return page;
    }

    /**
     * 前台查询某篇文章已通过的评论。
     */
    public List<Comment> listByArticle(Long articleId) {
        return list(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getStatus, 1)
                .orderByDesc(Comment::getId));
    }

    /**
     * 前台提交评论，默认待审核。
     */
    public void submit(CommentSubmitDTO dto, String ip, String userAgent) {
        Article article = articleMapper.selectById(dto.getArticleId());
        if (article == null || !Integer.valueOf(1).equals(article.getStatus())) {
            throw new BizException(404, "文章不存在");
        }
        Comment comment = new Comment();
        comment.setArticleId(dto.getArticleId());
        comment.setNickname(dto.getNickname().trim());
        comment.setEmail(dto.getEmail());
        comment.setContent(dto.getContent().trim());
        comment.setStatus(0);
        comment.setIp(ip);
        comment.setUserAgent(userAgent);
        save(comment);
    }

    /**
     * 审核通过。
     */
    public void approve(Long id) {
        Comment comment = getById(id);
        if (comment == null) {
            throw new BizException(404, "评论不存在");
        }
        if (Integer.valueOf(1).equals(comment.getStatus())) {
            return;
        }
        update(new LambdaUpdateWrapper<Comment>().eq(Comment::getId, id).set(Comment::getStatus, 1));
        refreshCommentCount(comment.getArticleId());
    }

    /**
     * 驳回（重新置为待审核）。
     */
    public void reject(Long id) {
        Comment comment = getById(id);
        if (comment == null) {
            throw new BizException(404, "评论不存在");
        }
        update(new LambdaUpdateWrapper<Comment>().eq(Comment::getId, id).set(Comment::getStatus, 0));
        refreshCommentCount(comment.getArticleId());
    }

    /**
     * 删除评论。
     */
    public void deleteComment(Long id) {
        Comment comment = getById(id);
        if (comment == null) {
            return;
        }
        removeById(id);
        refreshCommentCount(comment.getArticleId());
    }

    private void refreshCommentCount(Long articleId) {
        if (articleId == null) {
            return;
        }
        Long count = count(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getStatus, 1));
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .set(Article::getCommentCount, count == null ? 0 : count.intValue()));
    }

    private void fillArticleTitle(List<Comment> comments) {
        if (CollectionUtils.isEmpty(comments)) {
            return;
        }
        List<Long> articleIds = comments.stream()
                .map(Comment::getArticleId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> titleMap = articleIds.isEmpty() ? Map.of()
                : articleMapper.selectBatchIds(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, Article::getTitle, (a, b) -> a));
        comments.forEach(c -> c.setArticleTitle(titleMap.get(c.getArticleId())));
    }
}
