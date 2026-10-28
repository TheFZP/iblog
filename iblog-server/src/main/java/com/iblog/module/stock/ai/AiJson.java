package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI 包里共用的 Jackson 实例。
 *
 * <p>不用注入 Spring 的 ObjectMapper：那个实例带着全局的日期格式与非空策略
 * （{@code default-property-inclusion: non_null}），而 SSE 事件要按字段原样下发，
 * 空值省掉会让前端拿到形状不稳定的对象。</p>
 */
final class AiJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private AiJson() {
    }
}
