package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量看图打标的结果（T7 档 3）。
 * <p>
 * 逐张上报：某一张看图失败不影响其余张（部分结果好过没有结果），
 * 因此 {@code suggestions[]} 里每条都自带 {@code ok}，模型可以只把成功的那些交给 batchEditPictures。
 * </p>
 */
@Data
public class VisionTagResult {

    /** 目标空间 id */
    private String spaceId;

    /** 本次请求的图片张数 */
    private int requested;

    /** 成功拿到建议的张数 */
    private int suggested;

    /** 因登录态失效等致命错误提前停止时，剩余未尝试的张数 */
    private int skipped;

    /** 逐张建议 */
    private List<VisionTagSuggestion> suggestions = new ArrayList<>();

    /** 给模型/用户的说明（如"建议尚未落库，需用户确认后再调 batchEditPictures"） */
    private String note;
}
