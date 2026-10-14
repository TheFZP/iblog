package com.iblog.module.controller.admin;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iblog.common.result.R;
import com.iblog.module.entity.Comment;
import com.iblog.module.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台评论管理。
 */
@Tag(name = "后台-评论管理")
@RestController
@RequestMapping("/api/admin/comments")
@RequiredArgsConstructor
public class AdminCommentController {

    private final CommentService commentService;

    @Operation(summary = "评论分页列表")
    @GetMapping
    public R<IPage<Comment>> page(@RequestParam(defaultValue = "1") long pageNum,
                                  @RequestParam(defaultValue = "10") long pageSize,
                                  @RequestParam(required = false) Integer status) {
        return R.ok(commentService.pageForAdmin(pageNum, pageSize, status));
    }

    @Operation(summary = "审核通过")
    @PutMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id) {
        commentService.approve(id);
        return R.ok();
    }

    @Operation(summary = "驳回")
    @PutMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id) {
        commentService.reject(id);
        return R.ok();
    }

    @Operation(summary = "删除评论")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        commentService.deleteComment(id);
        return R.ok();
    }
}
