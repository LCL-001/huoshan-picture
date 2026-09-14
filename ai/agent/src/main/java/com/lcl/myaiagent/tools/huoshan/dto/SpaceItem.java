package com.lcl.myaiagent.tools.huoshan.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 空间条目（T7 档 1）：只带"找空间、判断还有多少容量"需要的字段 */
@Data
public class SpaceItem {

    private Long id;

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
