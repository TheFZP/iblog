package com.iblog.module.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 文章新增 / 编辑请求。
 */
@Data
public class ArticleSaveDTO {

    private Long id;

    @NotBlank(message = "标题不能为空")
    private String title;

    private String slug;

    private String summary;

    @NotBlank(message = "正文不能为空")
    private String content;

    private String cover;

    private Long categoryId;

    private String author;

    /** 0 草稿 1 已发布 */
    private Integer status = 0;

    /** 1 置顶 */
    private Integer isTop = 0;

    private List<Long> tagIds;
}
