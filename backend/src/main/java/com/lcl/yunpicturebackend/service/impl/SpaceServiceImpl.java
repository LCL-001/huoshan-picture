package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceAddRequest;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceEditRequest;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceQueryRequest;
import com.lcl.yunpicturebackend.domain.dto.space.SpaceUpdateRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.SpaceVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.enums.SpaceLevelEnum;
import com.lcl.yunpicturebackend.enums.SpaceRoleEnum;
import com.lcl.yunpicturebackend.enums.SpaceTypeEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.mapper.SpaceMapper;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.service.PictureFileCleanupService;
import com.lcl.yunpicturebackend.utils.SqlSortUtils;
import com.lcl.yunpicturebackend.utils.TextSanitizeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * <p>
 * 空间 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-04-24
 */
@Service
@RequiredArgsConstructor
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space> implements ISpaceService {

    private final IUserService userService;
    private final TransactionTemplate transactionTemplate;
    private final ISpaceUserService spaceUserService;
    private final PictureFileCleanupService pictureFileCleanupService;

    /**
     * 图片服务：空间级联删除时逻辑删图片。PictureServiceImpl 构造期依赖 ISpaceService，
     * 这里必须 @Lazy 延迟解析打破循环依赖。
     */
    @Autowired
    @Lazy
    private IPictureService pictureService;
//    @Resource
//    @Lazy
//    private DynamicShardingManager dynamicShardingManager;
    private final ConcurrentHashMap<Long, Object> lockMap = new ConcurrentHashMap<>();// 锁对象

    @Override
    public Long addSpace(SpaceAddRequest spaceAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(spaceAddRequest == null, ErrorCode.PARAMS_ERROR);
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS
        spaceAddRequest.setSpaceName(TextSanitizeUtils.stripHtml(spaceAddRequest.getSpaceName()));
        User loginUser = userService.getLoginUser(request);
        // 将实体类和 DTO 进行转换
        Space space = new Space();
        BeanUtil.copyProperties(spaceAddRequest, space);
        // 设置默认值（空间名称和空间等级）
        if (StrUtil.isBlank(space.getSpaceName())) {
            space.setSpaceName("默认空间");
        }
        if (space.getSpaceLevel() == null) {
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());// 默认普通版
        }
        if (space.getSpaceType() == null) {
            space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());// 默认私有空间
        }
        // 填充数据
        this.fillSpaceBySpaceLevel(space);
        // 数据校验
        this.validSpace(space, true);
        // 权限校验
        Long userId = loginUser.getId();
        space.setUserId(userId);
        if (SpaceLevelEnum.COMMON.getValue() != space.getSpaceLevel() && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限创建指定级别空间");
        }
        // 针对用户加锁
        Object lock = lockMap.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            try {
                Long newSpaceId = transactionTemplate.execute(status -> {
                    boolean exists = this.lambdaQuery()
                            .eq(Space::getUserId, userId)
                            .eq(Space::getSpaceType, space.getSpaceType())
                            .exists();
                    ThrowUtils.throwIf(exists, ErrorCode.OPERATION_ERROR, "每个用户每类空间仅能有一个");
                    // 写入数据库
                    boolean save = this.save(space);
                    ThrowUtils.throwIf(!save, ErrorCode.OPERATION_ERROR, "创建空间失败，向数据库添加数据失败");
                    // 创建成功后，如果是团队空间，则关联新增团队成员记录
                    if (Objects.equals(space.getSpaceType(), SpaceTypeEnum.TEAM.getValue())) {
                        SpaceUser spaceUser = new SpaceUser();
                        spaceUser.setSpaceId(space.getId());
                        spaceUser.setUserId(userId);
                        spaceUser.setSpaceRole(SpaceRoleEnum.ADMIN.getValue());
                        boolean success = spaceUserService.save(spaceUser);
                        ThrowUtils.throwIf(!success, ErrorCode.OPERATION_ERROR, "创建团队成员记录失败，向数据库添加数据失败");
                    }
//                    // 创建分表
//                    dynamicShardingManager.createSpacePictureTable(space);
                    // 返回新写入的空间 id
                    return space.getId();
                });
                // 返回结果是包装类，可以做一些处理
                return Optional.ofNullable(newSpaceId).orElse(-1L);
            } finally {
                // 移除锁, 防止内存泄漏
                lockMap.remove(userId);
            }
        }
    }

    @Override
    public QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if (spaceQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = spaceQueryRequest.getId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Long userId = spaceQueryRequest.getUserId();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        Integer spaceType = spaceQueryRequest.getSpaceType();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();

        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName), "spaceName", spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel), "spaceLevel", spaceLevel);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceType), "spaceType", spaceType);
        // 排序（白名单校验，防止 ORDER BY 注入）
        String safeSortField = SqlSortUtils.sanitizeSortField(sortField, "id", "spaceName", "spaceLevel",
                "spaceType", "maxSize", "maxCount", "totalSize", "totalCount", "userId",
                "createTime", "editTime", "updateTime");
        queryWrapper.orderBy(StrUtil.isNotEmpty(safeSortField), "ascend".equals(sortOrder), safeSortField);
        return queryWrapper;
    }

    @Override
    public SpaceVO getSpaceVO(Space space, HttpServletRequest request) {
        // 对象转封装类
        SpaceVO spaceVO = SpaceVO.objToVo(space);
        // 关联查询用户信息
        Long userId = space.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            spaceVO.setUser(userVO);
        }
        return spaceVO;
    }

    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request) {
        List<Space> spaceList = spacePage.getRecords();
        Page<SpaceVO> spaceVOPage = new Page<>(spacePage.getCurrent(), spacePage.getSize(), spacePage.getTotal());
        if (CollUtil.isEmpty(spaceList)) {
            return spaceVOPage;
        }
        // 对象列表 => 封装对象列表
        List<SpaceVO> spaceVOList = spaceList.stream().map(SpaceVO::objToVo).collect(Collectors.toList());
        // 1. 关联查询用户信息
        Set<Long> userIdSet = spaceList.stream().map(Space::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            spaceVO.setUser(userService.getUserVO(user));
        });
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }

    @Override
    public void validSpace(Space space, boolean add) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = space.getId();
        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);
        Integer spaceType = space.getSpaceType();
        SpaceTypeEnum spaceTypeEnum = SpaceTypeEnum.getEnumByValue(spaceType);
        // 创建数据时校验
        if (add) {
            ThrowUtils.throwIf(StrUtil.isBlank(spaceName), ErrorCode.PARAMS_ERROR, "空间名称不能为空");

            ThrowUtils.throwIf(spaceLevel == null, ErrorCode.PARAMS_ERROR, "空间级别不能为空");

            ThrowUtils.throwIf(spaceType == null, ErrorCode.PARAMS_ERROR, "空间类型不能为空");
        }
        // 修改数据时校验
        if (StrUtil.isNotBlank(spaceName) && spaceName.length() > 30) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称过长");
        }
        if (spaceLevel != null && spaceLevelEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不存在");
        }
        if (spaceType != null && spaceTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间类型不存在");
        }
    }

    @Override
    public void fillSpaceBySpaceLevel(Space space) {
        // 根据空间级别，填充空间限额
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(space.getSpaceLevel());
        if (spaceLevelEnum != null) {
            long maxSize = spaceLevelEnum.getMaxSize();
            if (space.getMaxSize() == null) {
                space.setMaxSize(maxSize);
            }
            long maxCount = spaceLevelEnum.getMaxCount();
            if (space.getMaxCount() == null) {
                space.setMaxCount(maxCount);
            }
        }
    }

    @Override
    public void deleteSpace(DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        Space oldSpace = getById(id);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        this.checkSpaceAuth(oldSpace, loginUser);
        this.deleteSpaceCascade(oldSpace);
    }

    /**
     * 删除用户的级联清理：名下空间逐个走空间级联，再移除其在他人团队空间的成员关系。
     * 账号行删除与会话踢除由调用方（UserController）负责，故本方法在删号前调用，
     * 失败时用户行仍在、可重试收敛。
     * 注意：用户上传到他人团队空间的图片保留（团队内容不随个人账号删除）。
     */
    @Override
    public void deleteUserCascade(long userId) {
        // 名下空间逐个级联删除：每空间独立事务，单个失败即抛出，已删部分不受影响
        List<Space> ownedSpaces = this.lambdaQuery()
                .eq(Space::getUserId, userId)
                .list();
        for (Space space : ownedSpaces) {
            this.deleteSpaceCascade(space);
        }
        // 移除在他人团队空间的成员关系
        spaceUserService.remove(new QueryWrapper<SpaceUser>().eq("userId", userId));
    }

    /**
     * 空间级联删除核心：单事务内逻辑删空间行、成员记录与空间下全部图片，
     * 事务提交后对无存活 URL 引用的图片异步清理 COS 文件。
     * <p>
     * 图片列表缓存不在此处失效：空间删除后其缓存条目已不可达
     * （空间态接口全部要求空间存活），残余由 TTL 兜底。
     *
     * @param oldSpace 待删除的空间（须为存活记录）
     */
    private void deleteSpaceCascade(Space oldSpace) {
        long id = oldSpace.getId();
        List<Picture> removedPictures = transactionTemplate.execute(status -> {
            // 事务内先捕获待删图片，提交后据此清理 COS 文件
            List<Picture> spacePictures = pictureService.lambdaQuery()
                    .eq(Picture::getSpaceId, id)
                    .list();
            ThrowUtils.throwIf(!this.removeById(id), ErrorCode.OPERATION_ERROR, "删除空间失败");
            // 清理该空间的所有成员记录
            spaceUserService.remove(new QueryWrapper<SpaceUser>().eq("spaceId", id));
            // 逻辑删空间下全部图片
            pictureService.remove(new QueryWrapper<Picture>().eq("spaceId", id));
            return spacePictures;
        });
        this.cleanupRemovedPictures(removedPictures);
    }

    /**
     * 事务提交后清理被删图片的 COS 文件：URL 仍被其它存活记录引用时跳过
     * （查询自动排除已逻辑删的行，与 PictureServiceImpl#cleanupPictureFile 同口径）
     */
    private void cleanupRemovedPictures(List<Picture> removedPictures) {
        if (CollUtil.isEmpty(removedPictures)) {
            return;
        }
        for (Picture picture : removedPictures) {
            if (StrUtil.isBlank(picture.getUrl())) {
                continue;
            }
            long refCount = pictureService.lambdaQuery()
                    .eq(Picture::getUrl, picture.getUrl())
                    .count();
            if (refCount == 0) {
                pictureFileCleanupService.clearPictureFile(picture);
            }
        }
    }

    @Override
    public void checkSpaceAuth(Space oldSpace, User loginUser) {
        // 空间创建者或系统管理员直接放行
        if (oldSpace.getUserId().equals(loginUser.getId()) || userService.isAdmin(loginUser)) {
            return;
        }
        // 团队空间：检查是否为团队管理员
        if (Objects.equals(oldSpace.getSpaceType(), SpaceTypeEnum.TEAM.getValue())) {
            SpaceUser spaceUser = spaceUserService.getOne(new QueryWrapper<SpaceUser>()
                    .eq("spaceId", oldSpace.getId())
                    .eq("userId", loginUser.getId()));
            if (spaceUser != null && "admin".equals(spaceUser.getSpaceRole())) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
    }

    @Override
    public Page<Space> listMyRelatedSpaceByPage(User loginUser, SpaceQueryRequest spaceQueryRequest,
                                                long current, long size) {
        QueryWrapper<Space> queryWrapper = getQueryWrapper(spaceQueryRequest);
        // 可见范围 = 本人拥有的空间 + 已加入的团队空间；请求里的筛选条件在此范围内继续生效
        List<Long> joinedSpaceIds = spaceUserService.lambdaQuery()
                .select(SpaceUser::getSpaceId)
                .eq(SpaceUser::getUserId, loginUser.getId())
                .list()
                .stream()
                .map(SpaceUser::getSpaceId)
                .collect(Collectors.toList());
        queryWrapper.and(qw -> {
            qw.eq("userId", loginUser.getId());
            if (CollUtil.isNotEmpty(joinedSpaceIds)) {
                qw.or().in("id", joinedSpaceIds);
            }
        });
        return this.page(new Page<>(current, size), queryWrapper);
    }

    @Override
    public void updateSpace(SpaceUpdateRequest spaceUpdateRequest, HttpServletRequest request) {
        if (spaceUpdateRequest == null || spaceUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 将实体类和 DTO 进行转换
        Space space = new Space();
        BeanUtils.copyProperties(spaceUpdateRequest, space);
        // 自动填充数据
        this.fillSpaceBySpaceLevel(space);
        // 数据校验
        this.validSpace(space, false);
        // 判断是否存在
        long id = spaceUpdateRequest.getId();
        Space oldSpace = getById(id);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR);
        // 操作数据库
        boolean result = this.updateById(space);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
    }

    @Override
    public void editSpace(SpaceEditRequest spaceEditRequest, HttpServletRequest request) {
        if (spaceEditRequest == null || spaceEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS
        spaceEditRequest.setSpaceName(TextSanitizeUtils.stripHtml(spaceEditRequest.getSpaceName()));
        // 在此处将实体类和 DTO 进行转换
        Space space = new Space();
        BeanUtils.copyProperties(spaceEditRequest, space);
        // 设置编辑时间
        space.setEditTime(new Date());
        // 自动填充数据
        this.fillSpaceBySpaceLevel(space);
        // 数据校验
        this.validSpace(space, false);
        User loginUser = userService.getLoginUser(request);
        // 判断是否存在
        long id = spaceEditRequest.getId();
        Space oldSpace = this.getById(id);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可编辑
        this.checkSpaceAuth(oldSpace, loginUser);
        // 操作数据库
        boolean result = this.updateById(space);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
    }
}
