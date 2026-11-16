package com.zjlt.preaudit.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应体
 */
public record PageResult<T>(long total, long page, long size, List<T> records) {

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(mapper).toList());
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(0, page, size, List.of());
    }
}
