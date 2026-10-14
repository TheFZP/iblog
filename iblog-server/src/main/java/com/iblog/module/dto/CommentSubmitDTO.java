package com.iblog.module.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 前台提交评论。
 */
@Data
public class CommentSubmitDTO {

    @NotNull(message = "文章不能为空")
    private Long articleId;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 30, message = "昵称最长 30 个字符")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容最长 1000 个字符")
    private String content;
}
