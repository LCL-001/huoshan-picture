package com.lcl.myaiagent.common;

import com.lcl.myaiagent.constant.CommonConstant;
import lombok.Data;

/**
 * 分页请求基类
 */
@Data
public class PageRequest {

    private int current = 1;

    private int pageSize = 10;

    private String sortField;

    private String sortOrder = CommonConstant.SORT_ORDER_ASC;
}
