package com.iblog.module.vo;

import com.iblog.module.entity.Article;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 后台仪表盘统计。
 */
@Data
@Builder
public class DashboardVO {

    private long articleCount;

    private long publishedCount;

    private long draftCount;

    private long categoryCount;

    private long tagCount;

    private long commentCount;

    private long pendingCommentCount;

    private long totalViewCount;

    /** 最新文章 */
    private List<Article> latestArticles;
}
