package com.lcl.yunpicturebackend.domain.dto.picture;

import lombok.Data;

import java.util.List;

/**
 * 回收站批量操作请求（T50）：恢复 / 彻底删除共用。
 */
@Data
public class PictureTrashBatchRequest {

    /**
     * 要操作的图片 id 列表
     */
    private List<Long> pictureIdList;
}
