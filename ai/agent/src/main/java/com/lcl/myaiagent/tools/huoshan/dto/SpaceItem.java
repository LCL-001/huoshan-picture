package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 空间条目（T7 档 1）：只带"找空间、判断还有多少容量"需要的字段 */
@Data
public class SpaceItem {

    /**
     * 空间 id。**字符串而非 Long**：图库把 Long 统一序列化成字符串（JsonConfig 的 ToStringSerializer）防 JS 精度丢失，
     * 这里再转回数字会让模型把 19 位雪花 id 当数字读、写回时被截断（实测 2099389400592543745 → 2099389400592543700）。
     */
    private String id;

    private String spaceName;

    /** 空间类型：0-私有 1-团队 */
    private Integer spaceType;

    /** 空间级别：0-普通版 1-专业版 2-旗舰版 */
    private Integer spaceLevel;

    private Long totalCount;

    private Long maxCount;

    private Long totalSize;

    private Long maxSize;

    /** 当前用户在该空间的权限点，模型据此判断能不能改 */
    private List<String> permissionList = new ArrayList<>();
}
