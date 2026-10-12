package com.lcl.yunpicturebackend.domain.dto.picture;

import com.lcl.yunpicturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 回收站列表查询（T50）：分页参数即可，归属过滤由服务端按登录用户判定。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PictureTrashQueryRequest extends PageRequest {
}
