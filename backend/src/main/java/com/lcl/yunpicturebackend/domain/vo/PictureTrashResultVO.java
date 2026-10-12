package com.lcl.yunpicturebackend.domain.vo;

import lombok.Data;

/**
 * 回收站批量操作的逐条结果（T50）：一张的失败不影响同批其它张，逐条如实上报。
 */
@Data
public class PictureTrashResultVO {

    private Long pictureId;

    private boolean ok;

    private String message;

    public static PictureTrashResultVO ok(Long pictureId, String message) {
        PictureTrashResultVO vo = new PictureTrashResultVO();
        vo.setPictureId(pictureId);
        vo.setOk(true);
        vo.setMessage(message);
        return vo;
    }

    public static PictureTrashResultVO fail(Long pictureId, String message) {
        PictureTrashResultVO vo = new PictureTrashResultVO();
        vo.setPictureId(pictureId);
        vo.setOk(false);
        vo.setMessage(message);
        return vo;
    }
}
