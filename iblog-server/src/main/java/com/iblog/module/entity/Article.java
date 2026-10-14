package com.iblog.module.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章。
 */
@Data
@TableName("blog_article")
public class Article {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** 别名，用于更友好的 URL，可为空 */
    private String slug;

    /** 摘要 */
    private String summary;

    /** 正文，Markdown 原文 */
    private String content;

    /** 封面图 URL */
    private String cover;

    private Long categoryId;

    private String author;

    /** 0 草稿 1 已发布 */
    private Integer status;

    /** 1 置顶 */
    private Integer isTop;

    private Integer viewCount;

    private Integer commentCount;

    private LocalDateTime publishTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ---------- 以下为非表字段，用于联表展示 ----------

    @TableField(exist = false)
    private String categoryName;

    @TableField(exist = false)
    private List<Long> tagIds;

    @TableField(exist = false)
    private List<String> tagNames;

    /** 上一篇（详情用） */
    @TableField(exist = false)
    private Long prevId;

    @TableField(exist = false)
    private String prevTitle;

    /** 下一篇（详情用） */
    @TableField(exist = false)
    private Long nextId;

    @TableField(exist = false)
    private String nextTitle;
}
