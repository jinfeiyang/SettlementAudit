package com.zjlt.preaudit.common.api;

import lombok.Data;

/**
 * 统一响应体：{ code, message, data }
 */
@Data
public class R<T> {

    public static final int OK = 0;
    public static final int FAIL = 500;

    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok() {
        return build(OK, "success", null);
    }

    public static <T> R<T> ok(T data) {
        return build(OK, "success", data);
    }

    public static <T> R<T> fail(String message) {
        return build(FAIL, message, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return build(code, message, null);
    }

    private static <T> R<T> build(int code, String message, T data) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.data = data;
        return r;
    }
}
