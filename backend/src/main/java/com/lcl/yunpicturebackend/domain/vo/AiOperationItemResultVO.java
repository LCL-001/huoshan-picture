package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

import java.io.Serializable;

/** URL 入库逐条结果（T27.5）：只带可展示的业务结果，不带 URL 与内部异常原文 */
@Data
public class AiOperationItemResultVO implements Serializable {

    /** 本条是否成功 */
    private Boolean ok;

    /** 可展示的原因（业务文案或固定文案，不含内部异常细节） */
    private String message;

    private static final long serialVersionUID = 1L;
}
