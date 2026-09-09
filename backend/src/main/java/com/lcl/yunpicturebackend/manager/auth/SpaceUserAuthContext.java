package com.lcl.yunpicturebackend.manager.auth;

import lombok.Data;

/**
 * SpaceUserAuthContext
 * 空间授权上下文，仅包含从请求中提取的 id 类标量字段。
 * 注意：不得携带任何对象字段（space/spaceUser/picture 等），
 * 空间归属与角色一律以数据库查询结果为准，防止请求体伪造角色提权。
 */
@Data
public class SpaceUserAuthContext {

    /**
     * 临时参数，不同请求对应的 id 可能不同
     */
    private Long id;

    /**
     * 图片 ID
     */
    private Long pictureId;

    /**
     * 空间 ID
     */
    private Long spaceId;

    /**
     * 空间用户 ID
     */
    private Long spaceUserId;
}
