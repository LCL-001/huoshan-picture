package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.domain.dto.post.PostAddRequest;
import com.lcl.yunpicturebackend.domain.dto.post.PostEditRequest;
import com.lcl.yunpicturebackend.domain.dto.post.PostQueryRequest;
import com.lcl.yunpicturebackend.domain.po.Post;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PostVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.mapper.PostMapper;
import com.lcl.yunpicturebackend.service.IPostService;
import com.lcl.yunpicturebackend.service.ISocialService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.utils.TextSanitizeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements IPostService {

    private final IUserService userService;
    private final StringRedisTemplate stringRedisTemplate;

    @Lazy
    private final ISocialService socialService;

    private static final String POST_VIEW_USERS_KEY = "post:view:users:";
    private static final String LIKE_USERS_KEY = "post:like:users:";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long publishPost(PostAddRequest request, Long userId) {
        ThrowUtils.throwIf(request == null || StrUtil.isBlank(request.getTitle()), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(request.getContent()), ErrorCode.PARAMS_ERROR, "内容不能为空");
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS（images 为 URL 字段不清洗）
        request.setTitle(TextSanitizeUtils.stripHtml(request.getTitle()));
        request.setContent(TextSanitizeUtils.stripHtml(request.getContent()));
        request.setTags(TextSanitizeUtils.stripHtml(request.getTags()));
        Post post = new Post();
        post.setUserId(userId);
        post.setTitle(request.getTitle());
        post.setContent(request.getContent());
        post.setImages(request.getImages());
        post.setTags(request.getTags());
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setViewCount(0);
        post.setStatus(0);
        this.save(post);
        socialService.pushToFollowersTimeline(userId, post.getId());
        return post.getId();
    }

    @Override
    public PostVO getPostVO(Post post, HttpServletRequest request) {
        if (post == null) return null;
        PostVO vo = PostVO.objToVo(post);
        // tags 转为列表
        if (StrUtil.isNotBlank(post.getTags())) {
            vo.setTagList(Arrays.asList(post.getTags().split(",")));
        }
        // 关联用户
        Long userId = post.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            vo.setUser(userService.getUserVO(user));
        }
        // 当前用户是否已点赞
        try {
            User loginUser = userService.getLoginUser(request);
            Boolean liked = stringRedisTemplate.opsForSet().isMember(LIKE_USERS_KEY + post.getId(), String.valueOf(loginUser.getId()));
            vo.setIsLiked(liked != null && liked);
        } catch (Exception ignored) {
            vo.setIsLiked(false);
        }
        return vo;
    }

    @Override
    public Page<PostVO> getPostVOPage(PostQueryRequest request, HttpServletRequest httpRequest) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Post::getStatus, 0);
        if (request.getUserId() != null) {
            wrapper.eq(Post::getUserId, request.getUserId());
        }
        if (StrUtil.isNotBlank(request.getKeyword())) {
            wrapper.and(w -> w.like(Post::getTitle, request.getKeyword())
                            .or().like(Post::getContent, request.getKeyword()));
        }
        wrapper.orderByDesc(Post::getCreateTime);
        Page<Post> page = this.page(new Page<>(request.getCurrent(), request.getPageSize()), wrapper);

        // 批量填充作者信息与点赞状态，避免 N+1
        List<PostVO> voList = toPostVOList(page.getRecords(), httpRequest);

        Page<PostVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 获取当前登录用户，未登录时返回 null
     */
    private User getLoginUserOrNull(HttpServletRequest httpRequest) {
        try {
            return userService.getLoginUser(httpRequest);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 批量转换帖子 VO：一次批量查询作者信息 + pipeline 查询点赞状态
     */
    private List<PostVO> toPostVOList(List<Post> posts, HttpServletRequest httpRequest) {
        if (CollUtil.isEmpty(posts)) {
            return new ArrayList<>();
        }
        // 批量查询作者信息
        Set<Long> userIds = posts.stream().map(Post::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> userMap = CollUtil.isEmpty(userIds) ? Collections.emptyMap()
                : userService.listByIds(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));
        // 当前登录用户只判断一次
        User loginUser = getLoginUserOrNull(httpRequest);
        // pipeline 批量查询点赞状态
        Map<Long, Boolean> likedMap = new HashMap<>();
        if (loginUser != null) {
            List<Object> likedList = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] member = String.valueOf(loginUser.getId()).getBytes(StandardCharsets.UTF_8);
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
    public PostVO getPostVOById(Long id, HttpServletRequest request) {
        Post post = this.getById(id);
        ThrowUtils.throwIf(post == null || post.getStatus() != 0, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        try {
            User loginUser = userService.getLoginUser(request);
            String key = POST_VIEW_USERS_KEY + id;
            Long added = stringRedisTemplate.opsForSet().add(key, String.valueOf(loginUser.getId()));
            if (added != null && added > 0) {
                // 原子自增，避免并发读-改-写丢更新
                this.lambdaUpdate().eq(Post::getId, id).setSql("viewCount = viewCount + 1").update();
                post.setViewCount((post.getViewCount() == null ? 0 : post.getViewCount()) + 1);
            }
        } catch (Exception ignored) {
            // 未登录用户正常计数
            this.lambdaUpdate().eq(Post::getId, id).setSql("viewCount = viewCount + 1").update();
            post.setViewCount((post.getViewCount() == null ? 0 : post.getViewCount()) + 1);
        }
        return getPostVO(post, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editPost(PostEditRequest request, Long userId) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        Post post = this.getById(request.getId());
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR);
        ThrowUtils.throwIf(!post.getUserId().equals(userId), ErrorCode.NO_AUTH_ERROR, "只能编辑自己的帖子");
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS（images 为 URL 字段不清洗）
        request.setTitle(TextSanitizeUtils.stripHtml(request.getTitle()));
        request.setContent(TextSanitizeUtils.stripHtml(request.getContent()));
        request.setTags(TextSanitizeUtils.stripHtml(request.getTags()));
        if (StrUtil.isNotBlank(request.getTitle())) post.setTitle(request.getTitle());
        if (StrUtil.isNotBlank(request.getContent())) post.setContent(request.getContent());
        if (request.getImages() != null) post.setImages(request.getImages());
        if (request.getTags() != null) post.setTags(request.getTags());
        this.updateById(post);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long id, Long userId) {
        Post post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR);
        ThrowUtils.throwIf(!post.getUserId().equals(userId), ErrorCode.NO_AUTH_ERROR, "只能删除自己的帖子");
        this.removeById(id);
    }
}
