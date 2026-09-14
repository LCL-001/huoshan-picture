package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量按 URL 上传的结果（T7 档 3）。
 * <p>
 * 图库的 {@code POST /picture/upload/url} 是**单张**端点，一次调用一个信封（成功给 PictureVO，
 * 失败给业务码）——所以"批量"在本工具内部循环完成，逐条成败都在 {@code items} 里，
 * 部分失败不抛异常（设计文档 L60/L64：一步完成一批、逐条报成功/失败/额度）。
 * </p>
 */
@Data
public class BatchUploadResult {

    /** 目标空间 id */
    private String spaceId;

    /** 本次请求的 URL 条数 */
    private int requested;

    /** 成功入库条数 */
    private int succeeded;

    /** 失败条数 */
    private int failed;

    /** 因登录态失效等致命错误提前停止时，剩余未尝试的条数 */
    private int skipped;

    /** 给模型/用户的说明（如"登录态失效，剩余 N 条未尝试"） */
    private String note;

    /** 逐条结果 */
    private List<UploadResultItem> items = new ArrayList<>();
}
