package com.lcl.myaiagent.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * SQL 工具类
 */
public class SqlUtils {

    /**
     * 校验排序字段是否合法（防 SQL 注入）
     */
    public static boolean validSortField(String sortField) {
        if (StringUtils.isBlank(sortField)) {
            return false;
        }
        return !StringUtils.containsAny(sortField, "=", "(", ")", " ");
    }
}
