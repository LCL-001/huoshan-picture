package com.lcl.myaiagent.common;

import java.io.Serializable;
import lombok.Data;

/**
 * 通用删除请求
 */
@Data
public class DeleteRequest implements Serializable {

    private Long id;

    private static final long serialVersionUID = 1L;
}
