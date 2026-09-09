package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.domain.po.*;
import com.lcl.yunpicturebackend.domain.vo.PostVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.mapper.PostMapper;
import com.lcl.yunpicturebackend.mapper.UserNotificationMapper;
import com.lcl.yunpicturebackend.service.ISocialService;
import com.lcl.yunpicturebackend.service.IUserFollowService;
import com.lcl.yunpicturebackend.service.IUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SocialServiceImpl implements ISocialService {

    private final StringRedisTemplate stringRedisTemplate;
    private final IUserFollowService userFollowService;
    private final PostMapper postMapper;
    private final IUserService userService;
    private final UserNotificationMapper notificationMapper;

    private static final String TIMELINE_KEY = "user:timeline:";
    private static final String UNREAD_COUNT_KEY = "user:unread:";
    private static final String LIKE_USERS_KEY = "post:like:users:";
    private static final int TIMELINE_MAX = 200;
    private static final int FAN_THRESHOLD = 1000;

    @Override
    @Async
    public void pushToFollowersTimeline(Long userId, Long postId) {
        long fanCount = userFollowService.getFollowerCount(userId);
        if (fanCount > FAN_THRESHOLD) return;
        List<UserFollow> followers = userFollowService.list(
                new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFolloweeId, userId));
        if (CollUtil.isEmpty(followers)) return;
        long score = System.currentTimeMillis();
        // pipeline 批量写入，避免每个粉丝一次 Redis 往返
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] value = String.valueOf(postId).getBytes(StandardCharsets.UTF_8);
            for (UserFollow f : followers) {
                connection.zAdd((TIMELINE_KEY + f.getFollowerId()).getBytes(StandardCharsets.UTF_8), score, value);
            }
            return null;
        });
        // pipeline 批量获取各时间线长度，仅对超限的裁剪
        List<Object> sizes = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (UserFollow f : followers) {
                connection.zCard((TIMELINE_KEY + f.getFollowerId()).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        for (int i = 0; i < followers.size(); i++) {
            Object sizeObj = sizes.get(i);
            long size = sizeObj instanceof Long ? (Long) sizeObj : 0;
            if (size > TIMELINE_MAX) {
                String key = TIMELINE_KEY + followers.get(i).getFollowerId();
                stringRedisTemplate.opsForZSet().removeRange(key, 0, size - TIMELINE_MAX - 1);
            }
        }
    }

    @Override
    public Page<PostVO> getTimeline(Long userId, int current, int pageSize) {
        String key = TIMELINE_KEY + userId;
        long start = (long) (current - 1) * pageSize;
        long end = start + pageSize - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(key, start, end);

        List<Post> posts = new ArrayList<>();
        if (CollUtil.isNotEmpty(members)) {
            // 批量查询帖子并保持 ZSet 的时间倒序
            List<Long> postIds = members.stream().map(Long::parseLong).collect(Collectors.toList());
            Map<Long, Post> postMap = postMapper.selectBatchIds(postIds).stream()
                    .collect(Collectors.toMap(Post::getId, p -> p));
            postIds.forEach(postId -> {
                Post post = postMap.get(postId);
                if (post != null && post.getStatus() == 0) {
                    posts.add(post);
                }
            });
        }

        // 数据不足，补 Pull
        if (posts.size() < pageSize) {
            List<UserFollow> follows = userFollowService.list(
                    new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId, userId));
            if (CollUtil.isNotEmpty(follows)) {
                Set<Long> existingIds = posts.stream().map(Post::getId).collect(Collectors.toSet());
                List<Long> followeeIds = follows.stream().map(UserFollow::getFolloweeId).collect(Collectors.toList());
                List<Post> supplement = postMapper.selectList(new LambdaQueryWrapper<Post>()
                        .in(Post::getUserId, followeeIds)
                        .eq(Post::getStatus, 0)
                        .notIn(CollUtil.isNotEmpty(existingIds), Post::getId, existingIds)
                        .orderByDesc(Post::getCreateTime)
                        .last("LIMIT " + (pageSize - posts.size())));
                posts.addAll(supplement);
            }
        }

        // 批量填充用户信息与点赞状态，避免 N+1
        List<PostVO> result = toPostVOList(posts, userId);

        Page<PostVO> page = new Page<>(current, pageSize);
        page.setRecords(result);
        Long total = stringRedisTemplate.opsForZSet().size(key);
        page.setTotal(total != null ? total : 0);
        return page;
    }

    @Override
    public void sendNotification(Long userId, Long fromUserId, String type, Long targetId, String summary) {
        UserNotification n = new UserNotification();
        n.setUserId(userId);
        n.setFromUserId(fromUserId);
        n.setType(type);
        n.setTargetId(targetId);
        n.setSummary(summary);
        n.setIsRead(0);
        notificationMapper.insert(n);
        stringRedisTemplate.opsForValue().increment(UNREAD_COUNT_KEY + userId);
    }

    @Override
    public Page<UserNotification> listNotifications(Long userId, int current, int pageSize) {
        LambdaQueryWrapper<UserNotification> w = new LambdaQueryWrapper<>();
        w.eq(UserNotification::getUserId, userId).orderByDesc(UserNotification::getCreateTime);
        return notificationMapper.selectPage(new Page<>(current, pageSize), w);
    }

    @Override
    public long getUnreadCount(Long userId) {
        String c = stringRedisTemplate.opsForValue().get(UNREAD_COUNT_KEY + userId);
        return c != null ? Long.parseLong(c) : 0;
    }

    /**
     * 批量转换帖子 VO：一次批量查询作者信息 + pipeline 查询点赞状态，避免 N+1
     */
    private List<PostVO> toPostVOList(List<Post> posts, Long currentUserId) {
        if (CollUtil.isEmpty(posts)) {
            return new ArrayList<>();
        }
        // 批量查询作者信息
        Set<Long> userIds = posts.stream().map(Post::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> userMap = CollUtil.isEmpty(userIds) ? Collections.emptyMap()
                : userService.listByIds(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));
        // pipeline 批量查询当前用户是否点赞
        Map<Long, Boolean> likedMap = new HashMap<>();
        if (currentUserId != null) {
            List<Object> likedList = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] member = String.valueOf(currentUserId).getBytes(StandardCharsets.UTF_8);
                for (Post post : posts) {
                    connection.sIsMember((LIKE_USERS_KEY + post.getId()).getBytes(StandardCharsets.UTF_8), member);
                }
                return null;
            });
            for (int i = 0; i < posts.size(); i++) {
                likedMap.put(posts.get(i).getId(), Boolean.TRUE.equals(likedList.get(i)));
            }
        }
        return posts.stream().map(post -> {
            PostVO vo = PostVO.objToVo(post);
            if (StrUtil.isNotBlank(post.getTags())) {
                vo.setTagList(Arrays.asList(post.getTags().split(",")));
            }
            User user = post.getUserId() != null ? userMap.get(post.getUserId()) : null;
            vo.setUser(userService.getUserVO(user));
            vo.setIsLiked(likedMap.getOrDefault(post.getId(), false));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public void clearUnread(Long userId) {
        stringRedisTemplate.delete(UNREAD_COUNT_KEY + userId);
    }

    @Override
    public void clearNotifications(Long userId) {
        LambdaQueryWrapper<UserNotification> w = new LambdaQueryWrapper<>();
        w.eq(UserNotification::getUserId, userId);
        notificationMapper.delete(w);
        stringRedisTemplate.delete(UNREAD_COUNT_KEY + userId);
    }

    @Override
    public void deleteNotification(Long notificationId, Long userId) {
        UserNotification n = notificationMapper.selectById(notificationId);
        if (n != null && n.getUserId().equals(userId)) {
            notificationMapper.deleteById(notificationId);
            if (n.getIsRead() == 0) {
                String c = stringRedisTemplate.opsForValue().get(UNREAD_COUNT_KEY + userId);
                if (c != null) {
                    long v = Long.parseLong(c) - 1;
                    stringRedisTemplate.opsForValue().set(UNREAD_COUNT_KEY + userId, String.valueOf(Math.max(0, v)));
                }
            }
        }
    }

    @Override
    public void markAsRead(Long notificationId, Long userId) {
        UserNotification n = notificationMapper.selectById(notificationId);
        if (n != null && n.getUserId().equals(userId)) {
            n.setIsRead(1);
            notificationMapper.updateById(n);
            String c = stringRedisTemplate.opsForValue().get(UNREAD_COUNT_KEY + userId);
            if (c != null) {
                long v = Long.parseLong(c) - 1;
                stringRedisTemplate.opsForValue().set(UNREAD_COUNT_KEY + userId, String.valueOf(Math.max(0, v)));
            }
        }
    }
}
