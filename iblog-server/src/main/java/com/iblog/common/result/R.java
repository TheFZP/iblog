package com.iblog.common.result;

import lombok.Data;

/**
 * 统一响应体。
 *
 * @param <T> 数据类型
 */
@Data
public class R<T> {

    /** 业务状态码，200 表示成功 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    public static <T> R<T> ok() {
        return build(200, "操作成功", null);
    }

    public static <T> R<T> ok(T data) {
        return build(200, "操作成功", data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return build(200, msg, data);
    }

    public static <T> R<T> fail(String msg) {
        return build(500, msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return build(code, msg, null);
    }

    private static <T> R<T> build(int code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}
