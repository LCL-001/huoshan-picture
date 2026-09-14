package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

/**
 * 逐条上传结果（T7 档 3）：成功给新图片 id 与名称，失败给图库业务码与原因，供模型逐条向用户汇报。
 */
@Data
public class UploadResultItem {

    /** 本条对应的图片 URL（原样回显，便于用户核对是哪一条失败） */
    private String fileUrl;

    /** 本条是否入库成功 */
    private boolean ok;

    /** 成功时的新图片 id（字符串） */
    private String pictureId;

    /** 成功时图库生成的图片名称 */
    private String name;

    /** 失败时的图库业务码（如 50001 空间额度不足 / 图片已存在） */
    private Integer code;

    /** 失败原因（图库原文，便于用户判断要不要重试） */
    private String message;
}
