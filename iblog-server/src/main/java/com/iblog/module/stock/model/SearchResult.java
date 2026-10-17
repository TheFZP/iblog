package com.iblog.module.stock.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票搜索结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    private String code;

    private String name;

    /** SH / SZ / HK / BJ */
    private String market;

    /** 展示用代码（如 SH600519），前端列表直接显示 */
    private String displayCode;
}
