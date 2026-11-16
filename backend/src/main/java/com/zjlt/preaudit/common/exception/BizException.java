package com.zjlt.preaudit.common.exception;

import lombok.Getter;

/**
 * 业务异常：由全局异常处理器统一转为 R.fail
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(500, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
