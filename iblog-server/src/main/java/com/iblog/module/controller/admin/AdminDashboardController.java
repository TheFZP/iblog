package com.iblog.module.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.iblog.common.result.R;
import com.iblog.module.entity.Article;
import com.iblog.module.entity.Comment;
import com.iblog.module.entity.User;
import com.iblog.module.service.ArticleService;
import com.iblog.module.service.CategoryService;
import com.iblog.module.service.CommentService;
import com.iblog.module.service.TagService;
import com.iblog.module.service.UserService;
import com.iblog.module.vo.DashboardVO;
import com.iblog.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台仪表盘。
 */
@Tag(name = "后台-仪表盘")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final ArticleService articleService;
    private final CategoryService categoryService;
    private final TagService tagService;
    private final CommentService commentService;
    private final UserService userService;

    @Operation(summary = "统计数据")
    @GetMapping
    public R<DashboardVO> dashboard() {
        List<Object> sumList = articleService.listObjs(
                new QueryWrapper<Article>().select("IFNULL(SUM(view_count), 0)"));
        long totalView = sumList.isEmpty() || sumList.get(0) == null
                ? 0L : ((Number) sumList.get(0)).longValue();

        List<Article> latest = articleService.list(new LambdaQueryWrapper<Article>()
                .select(Article.class, field -> !"content".equals(field.getColumn()))
                .orderByDesc(Article::getId)
                .last("limit 5"));
        articleService.fillMeta(latest);

        User current = userService.getById(UserContext.get());

        return R.ok(DashboardVO.builder()
                .articleCount(articleService.count())
                .publishedCount(articleService.count(new LambdaQueryWrapper<Article>().eq(Article::getStatus, 1)))
                .draftCount(articleService.count(new LambdaQueryWrapper<Article>().eq(Article::getStatus, 0)))
                .categoryCount(categoryService.count())
                .tagCount(tagService.count())
                .commentCount(commentService.count())
                .pendingCommentCount(commentService.count(new LambdaQueryWrapper<Comment>().eq(Comment::getStatus, 0)))
                .totalViewCount(totalView)
                .latestArticles(latest)
                .build());
    }
}
