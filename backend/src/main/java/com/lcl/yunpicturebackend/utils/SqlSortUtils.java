package com.lcl.yunpicturebackend.utils;

import cn.hutool.core.util.StrUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 排序字段白名单校验，防止 ORDER BY 注入
 */
public class SqlSortUtils {

    private SqlSortUtils() {
    }

    /**
     * 排序字段在白名单内则原样返回，否则返回 null（调用方应忽略排序）
     *
     * @param sortField      前端传入的排序字段
     * @param allowedColumns 允许排序的数据库列名白名单
     * @return 白名单内的列名，或 null
     */
    public static String sanitizeSortField(String sortField, String... allowedColumns) {
        if (StrUtil.isBlank(sortField)) {
            return null;
        }
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedColumns));
        return allowed.contains(sortField) ? sortField : null;
    }
}
