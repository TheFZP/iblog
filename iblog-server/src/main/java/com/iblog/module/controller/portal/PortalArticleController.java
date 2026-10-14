package com.iblog.module.controller.portal;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iblog.common.result.R;
import com.iblog.module.dto.ArticleQueryDTO;
import com.iblog.module.dto.CommentSubmitDTO;
import com.iblog.module.entity.Article;
import com.iblog.module.entity.Comment;
import com.iblog.module.service.ArticleService;
import com.iblog.module.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 官网前台：文章与评论（公开访问，无需登录）。
 */
@Tag(name = "官网-文章与评论")
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalArticleController {

    private static final int MAX_UA_LENGTH = 255;

    private final ArticleService articleService;
    private final CommentService commentService;

    @Operation(summary = "文章分页列表")
    @GetMapping("/articles")
    public R<IPage<Article>> page(ArticleQueryDTO query) {
        return R.ok(articleService.pageForPortal(query));
    }

    @Operation(summary = "文章详情（累加浏览量）")
    @GetMapping("/articles/{id}")
    public R<Article> detail(@PathVariable Long id) {
        return R.ok(articleService.detail(id, true));
    }

    @Operation(summary = "文章评论列表（仅已通过）")
    @GetMapping("/articles/{id}/comments")
    public R<List<Comment>> comments(@PathVariable Long id) {
        return R.ok(commentService.listByArticle(id));
    }

    @Operation(summary = "提交评论（需后台审核）")
    @PostMapping("/comments")
    public R<Void> submitComment(@Valid @RequestBody CommentSubmitDTO dto, HttpServletRequest request) {
        commentService.submit(dto, getClientIp(request), truncate(request.getHeader("User-Agent")));
        return R.ok("评论已提交，待博主审核后展示", null);
    }

    private String getClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                int index = value.indexOf(',');
                return index > 0 ? value.substring(0, index).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_UA_LENGTH ? value.substring(0, MAX_UA_LENGTH) : value;
    }
}
