package com.iblog.module.dto;

import lombok.Data;

/**
 * 文章分页查询条件。
 */
@Data
public class ArticleQueryDTO {

    private Long pageNum = 1L;

    private Long pageSize = 10L;

    /** 标题模糊搜索 */
    private String keyword;

    private Long categoryId;

    private Long tagId;

    /** 0 草稿 1 已发布，null 表示全部 */
    private Integer status;

    /** 仅看置顶 */
    private Boolean onlyTop;
}
