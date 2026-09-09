package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.domain.dto.space.analyze.*;
import com.lcl.yunpicturebackend.domain.vo.space.analyze.*;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.mapper.SpaceMapper;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceAnalyzeService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SpaceAnalyzeServiceImpl extends ServiceImpl<SpaceMapper, Space> implements ISpaceAnalyzeService {

    private final IUserService userService;
    private final ISpaceService spaceService;
    private final IPictureService pictureService;

    /**
     * 获取空间使用分析数据
     *
     * @param spaceUsageAnalyzeRequest SpaceUsageAnalyzeRequest 请求参数
     * @param loginUser                当前登录用户
     * @return SpaceUsageAnalyzeResponse 分析结果
     */
    @Override
    public SpaceUsageAnalyzeResponse getSpaceUsageAnalyze(SpaceUsageAnalyzeRequest spaceUsageAnalyzeRequest, User loginUser) {
        ThrowUtils.throwIf(spaceUsageAnalyzeRequest == null, ErrorCode.PARAMS_ERROR);
        if (spaceUsageAnalyzeRequest.isQueryAll() || spaceUsageAnalyzeRequest.isQueryPublic()) {
            // 查询全部或公共图库逻辑
            // 仅管理员可以访问
            boolean isAdmin = userService.isAdmin(loginUser);
            ThrowUtils.throwIf(!isAdmin, ErrorCode.NO_AUTH_ERROR, "无权访问空间");
            // 统计公共图库的资源使用（聚合下推到 SQL，避免全量数据加载进内存）
            QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
            queryWrapper.select("COUNT(*) AS usedCount", "SUM(picSize) AS usedSize");
            if (!spaceUsageAnalyzeRequest.isQueryAll()) {
                queryWrapper.isNull("spaceId");
            }
            Map<String, Object> aggregateResult = pictureService.getBaseMapper().selectMaps(queryWrapper).get(0);
            long usedCount = aggregateResult.get("usedCount") != null ? ((Number) aggregateResult.get("usedCount")).longValue() : 0;
            long usedSize = aggregateResult.get("usedSize") != null ? ((Number) aggregateResult.get("usedSize")).longValue() : 0;
            // 封装返回结果
            SpaceUsageAnalyzeResponse spaceUsageAnalyzeResponse = new SpaceUsageAnalyzeResponse();
            spaceUsageAnalyzeResponse.setUsedSize(usedSize);
            spaceUsageAnalyzeResponse.setUsedCount(usedCount);
            // 公共图库无上限、无比例
            spaceUsageAnalyzeResponse.setMaxSize(null);
            spaceUsageAnalyzeResponse.setSizeUsageRatio(null);
            spaceUsageAnalyzeResponse.setMaxCount(null);
            spaceUsageAnalyzeResponse.setCountUsageRatio(null);
            return spaceUsageAnalyzeResponse;
        } else {
            // 查询指定空间
            Long spaceId = spaceUsageAnalyzeRequest.getSpaceId();
            ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR);
            // 获取空间信息
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

            // 权限校验：仅空间所有者或管理员可访问
            spaceService.checkSpaceAuth(space, loginUser);

            // 构造返回结果
            SpaceUsageAnalyzeResponse response = new SpaceUsageAnalyzeResponse();
            response.setUsedSize(space.getTotalSize());
            response.setMaxSize(space.getMaxSize());
            // 后端直接算好百分比，这样前端可以直接展示
            double sizeUsageRatio = NumberUtil.round(space.getTotalSize() * 100.0 / space.getMaxSize(), 2).doubleValue();
            response.setSizeUsageRatio(sizeUsageRatio);
            response.setUsedCount(space.getTotalCount());
            response.setMaxCount(space.getMaxCount());
            double countUsageRatio = NumberUtil.round(space.getTotalCount() * 100.0 / space.getMaxCount(), 2).doubleValue();
            response.setCountUsageRatio(countUsageRatio);
            return response;
        }
    }

    /**
     * 获取空间分类情况分析
     *
     * @param spaceCategoryAnalyzeRequest 空间分类情况分析请求
     * @param loginUser                   登录用户
     * @return 分类情况分析结果
     */
    @Override
    public List<SpaceCategoryAnalyzeResponse> getSpaceCategoryAnalyze(SpaceCategoryAnalyzeRequest spaceCategoryAnalyzeRequest, User loginUser) {
        ThrowUtils.throwIf(spaceCategoryAnalyzeRequest == null, ErrorCode.PARAMS_ERROR);
        // 检查权限
        checkSpaceAnalyzeAuth(spaceCategoryAnalyzeRequest, loginUser);
        // 构造查询条件
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        // 根据分析范围补充查询条件
        fillAnalyzeQueryWrapper(spaceCategoryAnalyzeRequest, queryWrapper);
        // 使用Mybatis-Plus实现分组查询
        queryWrapper.select("category AS category", "COUNT(*) AS count", "SUM(picSize) AS totalSize").groupBy("category");
        // 查询并转换结果
        return pictureService.getBaseMapper().selectMaps(queryWrapper).stream().map(result -> {
            String category = result.get("category") == null ? "未分类" : result.get("category").toString();
            long count = ((Number) result.get("count")).longValue();
            long totalSize = ((Number) result.get("totalSize")).longValue();
            return new SpaceCategoryAnalyzeResponse(category, count, totalSize);
        }).collect(Collectors.toList());
    }

    /**
     * 获取空间标签情况分析
     *
     * @param spaceTagAnalyzeRequest 空间标签情况分析请求
     * @param loginUser              登录用户
     * @return 标签情况分析结果
     */
    @Override
    public List<SpaceTagAnalyzeResponse> getSpaceTagAnalyze(SpaceTagAnalyzeRequest spaceTagAnalyzeRequest, User loginUser) {
        ThrowUtils.throwIf(spaceTagAnalyzeRequest == null, ErrorCode.PARAMS_ERROR);
        // 检查权限
        checkSpaceAnalyzeAuth(spaceTagAnalyzeRequest, loginUser);
        // 构造查询条件
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        // 根据分析范围补充查询条件
        fillAnalyzeQueryWrapper(spaceTagAnalyzeRequest, queryWrapper);
        // 查询符合条件的所有标签
        // TODO 标签为 JSON 数组结构，不适合直接 SQL 聚合；数据量大时可同步标签到独立表后聚合，当前保留内存统计
        queryWrapper.select("tags");
        List<String> tagsJsonList = pictureService.getBaseMapper().selectObjs(queryWrapper)
                .stream()
                .filter(ObjUtil::isNotNull)
                .map(Objects::toString)
                .collect(Collectors.toList());
        // 合并标签并统计使用次数
        Map<String, Long> tagConutMap = tagsJsonList.stream()
                .flatMap(tagsJson -> JSONUtil.toList(tagsJson, String.class).stream())
                .collect(Collectors.groupingBy(tag -> tag, Collectors.counting()));
        // 构造返回结果
        return tagConutMap.entrySet()
                .stream()
                .sorted((entry1, entry2) -> Long.compare(entry2.getValue(), entry1.getValue()))// 按使用次数降序排序
                .map(entry -> new SpaceTagAnalyzeResponse(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 获取空间大小情况分析
     *
     * @param spaceSizeAnalyzeRequest 空间大小情况分析请求
     * @param loginUser               登录用户
     * @return 大小情况分析结果
     */
    @Override
    public List<SpaceSizeAnalyzeResponse> getSpaceSizeAnalyze(SpaceSizeAnalyzeRequest spaceSizeAnalyzeRequest, User loginUser) {
        ThrowUtils.throwIf(spaceSizeAnalyzeRequest == null, ErrorCode.PARAMS_ERROR);

        // 检查权限
        checkSpaceAnalyzeAuth(spaceSizeAnalyzeRequest, loginUser);

        // 构造查询条件
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        fillAnalyzeQueryWrapper(spaceSizeAnalyzeRequest, queryWrapper);

        // 分段统计下推到 SQL（CASE WHEN 分组），避免全量 picSize 加载进内存
        queryWrapper.isNotNull("picSize");
        queryWrapper.select(
                "CASE WHEN picSize < 100 * 1024 THEN '0' " +
                        "WHEN picSize < 500 * 1024 THEN '1' " +
                        "WHEN picSize < 1024 * 1024 THEN '2' " +
                        "ELSE '3' END AS sizeRange",
                "COUNT(*) AS count"
        ).groupBy("sizeRange");
        Map<String, Long> rangeCountMap = new HashMap<>();
        pictureService.getBaseMapper().selectMaps(queryWrapper).forEach(result ->
                rangeCountMap.put(String.valueOf(result.get("sizeRange")), ((Number) result.get("count")).longValue()));

        // 定义分段范围，注意使用有序 Map
        Map<String, Long> sizeRanges = new LinkedHashMap<>();
        sizeRanges.put("<100KB", rangeCountMap.getOrDefault("0", 0L));
        sizeRanges.put("100KB-500KB", rangeCountMap.getOrDefault("1", 0L));
        sizeRanges.put("500KB-1MB", rangeCountMap.getOrDefault("2", 0L));
        sizeRanges.put(">1MB", rangeCountMap.getOrDefault("3", 0L));

        // 转换为响应对象
        return sizeRanges.entrySet().stream().map(entry -> new SpaceSizeAnalyzeResponse(entry.getKey(), entry.getValue())).collect(Collectors.toList());
    }

    /**
     * 获取空间用户情况分析
     *
     * @param spaceUserAnalyzeRequest 空间用户情况分析请求
     * @param loginUser               登录用户
     * @return 用户情况分析结果
     */
    @Override
    public List<SpaceUserAnalyzeResponse> getSpaceUserAnalyze(SpaceUserAnalyzeRequest spaceUserAnalyzeRequest, User loginUser) {
        ThrowUtils.throwIf(spaceUserAnalyzeRequest == null, ErrorCode.PARAMS_ERROR);
        // 检查权限
        checkSpaceAnalyzeAuth(spaceUserAnalyzeRequest, loginUser);

        // 构造查询条件
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        Long userId = spaceUserAnalyzeRequest.getUserId();
        queryWrapper.eq(ObjUtil.isNotNull(userId), "userId", userId);
        fillAnalyzeQueryWrapper(spaceUserAnalyzeRequest, queryWrapper);

        // 分析维度：每日、每周、每月
        String timeDimension = spaceUserAnalyzeRequest.getTimeDimension();
        switch (timeDimension) {
            case "day":
                queryWrapper.select("DATE_FORMAT(createTime, '%Y-%m-%d') AS period", "COUNT(*) AS count");
                break;
            case "week":
                queryWrapper.select("YEARWEEK(createTime) AS period", "COUNT(*) AS count");
                break;
            case "month":
                queryWrapper.select("DATE_FORMAT(createTime, '%Y-%m') AS period", "COUNT(*) AS count");
                break;
            default:
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的时间维度");
        }

        // 分组和排序
        queryWrapper.groupBy("period").orderByAsc("period");

        // 查询结果并转换
        List<Map<String, Object>> queryResult = pictureService.getBaseMapper().selectMaps(queryWrapper);
        return queryResult.stream().map(result -> {
            String period = result.get("period").toString();
            Long count = ((Number) result.get("count")).longValue();
            return new SpaceUserAnalyzeResponse(period, count);
        }).collect(Collectors.toList());
    }

    /**
     * 获取空间排行分析
     *
     * @param spaceRankAnalyzeRequest 空间排行分析请求
     * @param loginUser               登录用户
     * @return 空间排行分析结果
     */
    @Override
    public List<Space> getSpaceRankAnalyze(SpaceRankAnalyzeRequest spaceRankAnalyzeRequest, User loginUser) {
        ThrowUtils.throwIf(spaceRankAnalyzeRequest == null, ErrorCode.PARAMS_ERROR);

        // 仅管理员可查看空间排行
        ThrowUtils.throwIf(!userService.isAdmin(loginUser), ErrorCode.NO_AUTH_ERROR, "无权查看空间排行");

        // 构造查询条件
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("id", "spaceName", "userId", "totalSize").orderByDesc("totalSize").last("LIMIT " + spaceRankAnalyzeRequest.getTopN()); // 取前 N 名

        // 查询结果
        return spaceService.list(queryWrapper);
    }


    /**
     * 检查空间分析权限
     *
     * @param spaceAnalyzeRequest 空间分析请求
     * @param loginUser           登录用户
     */
    private void checkSpaceAnalyzeAuth(SpaceAnalyzeRequest spaceAnalyzeRequest, User loginUser) {
        // 检查权限
        if (spaceAnalyzeRequest.isQueryAll() || spaceAnalyzeRequest.isQueryPublic()) {
            // 全空间分析或公共图库分析权限校验，需要管理员权限
            ThrowUtils.throwIf(!userService.isAdmin(loginUser), ErrorCode.NO_AUTH_ERROR);
        } else {
            // 私有空间分析权限校验
            Long spaceId = spaceAnalyzeRequest.getSpaceId();
            ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR, "空间不存在");
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            spaceService.checkSpaceAuth(space, loginUser);
        }
    }

    /**
     * 填充空间分析查询条件
     *
     * @param spaceAnalyzeRequest 空间分析请求
     * @param queryWrapper        查询条件
     */
    private void fillAnalyzeQueryWrapper(SpaceAnalyzeRequest spaceAnalyzeRequest, QueryWrapper<Picture> queryWrapper) {
        if (spaceAnalyzeRequest.isQueryAll()) {
            return;
        }
        if (spaceAnalyzeRequest.isQueryPublic()) {
            queryWrapper.isNull("spaceId");
            return;
        }
        Long spaceId = spaceAnalyzeRequest.getSpaceId();
        if (spaceId != null) {
            queryWrapper.eq("spaceId", spaceId);
            return;
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "未指定查询范围");
    }
}
