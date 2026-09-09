package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.po.UserFollow;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.mapper.UserFollowMapper;
import com.lcl.yunpicturebackend.service.IUserFollowService;
import com.lcl.yunpicturebackend.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserFollowServiceImpl extends ServiceImpl<UserFollowMapper, UserFollow> implements IUserFollowService {

    private final StringRedisTemplate stringRedisTemplate;
    private final IUserService userService;

    private static final String FOLLOWING_KEY = "user:following:";
    private static final String FOLLOWING_COUNT_KEY = "user:following:count:";
    private static final String FOLLOWER_COUNT_KEY = "user:follower:count:";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFollow(Long followerId, Long followeeId) {
        if (ObjectUtil.hasEmpty(followerId, followeeId) || followerId.equals(followeeId))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能关注自己");
        LambdaQueryWrapper<UserFollow> w = new LambdaQueryWrapper<>();
        w.eq(UserFollow::getFollowerId, followerId).eq(UserFollow::getFolloweeId, followeeId);
        UserFollow exist = this.getOne(w);
        if (exist != null) {
            this.removeById(exist.getId());
            stringRedisTemplate.opsForSet().remove(FOLLOWING_KEY + followerId, String.valueOf(followeeId));
            stringRedisTemplate.opsForValue().decrement(FOLLOWING_COUNT_KEY + followerId);
            stringRedisTemplate.opsForValue().decrement(FOLLOWER_COUNT_KEY + followeeId);
            return false;
        }
        UserFollow f = new UserFollow();
        f.setFollowerId(followerId);
        f.setFolloweeId(followeeId);
        try {
            this.save(f);
        } catch (DuplicateKeyException e) {
            // 并发重复关注：唯一索引兜底，当前状态已是已关注，幂等返回
            return true;
        }
        stringRedisTemplate.opsForSet().add(FOLLOWING_KEY + followerId, String.valueOf(followeeId));
        stringRedisTemplate.opsForValue().increment(FOLLOWING_COUNT_KEY + followerId);
        stringRedisTemplate.opsForValue().increment(FOLLOWER_COUNT_KEY + followeeId);
        return true;
    }

    @Override
    public long getFollowingCount(Long userId) {
        String c = stringRedisTemplate.opsForValue().get(FOLLOWING_COUNT_KEY + userId);
        if (c != null) return Long.parseLong(c);
        long count = this.count(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId, userId));
        stringRedisTemplate.opsForValue().set(FOLLOWING_COUNT_KEY + userId, String.valueOf(count));
        return count;
    }

    @Override
    public long getFollowerCount(Long userId) {
        String c = stringRedisTemplate.opsForValue().get(FOLLOWER_COUNT_KEY + userId);
        if (c != null) return Long.parseLong(c);
        long count = this.count(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFolloweeId, userId));
        stringRedisTemplate.opsForValue().set(FOLLOWER_COUNT_KEY + userId, String.valueOf(count));
        return count;
    }

    @Override
    public boolean isFollowing(Long followerId, Long followeeId) {
        if (ObjectUtil.hasEmpty(followerId, followeeId)) return false;
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(FOLLOWING_KEY + followerId, String.valueOf(followeeId)));
    }

    @Override
    public List<UserVO> listFollowing(Long userId) {
        List<UserFollow> follows = this.list(
                new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId, userId)
                        .orderByDesc(UserFollow::getCreateTime));
        if (CollUtil.isEmpty(follows)) return Collections.emptyList();
        List<Long> followeeIds = follows.stream().map(UserFollow::getFolloweeId).collect(Collectors.toList());
        List<User> users = userService.listByIds(followeeIds);
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u));
        return followeeIds.stream()
                .map(id -> {
                    User u = userMap.get(id);
                    return u != null ? userService.getUserVO(u) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
