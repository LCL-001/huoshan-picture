package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.domain.po.Post;
import com.lcl.yunpicturebackend.domain.po.PostComment;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.mapper.PostCommentMapper;
import com.lcl.yunpicturebackend.service.IPostCommentService;
import com.lcl.yunpicturebackend.service.IPostService;
import com.lcl.yunpicturebackend.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostCommentServiceImpl extends ServiceImpl<PostCommentMapper, PostComment> implements IPostCommentService {

    private final IUserService userService;
    private final IPostService postService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addComment(Long postId, Long userId, Long parentId, Long replyToUserId, String content) {
        if (ObjectUtil.hasEmpty(postId, userId, content)) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        PostComment comment = new PostComment();
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setParentId(parentId != null ? parentId : 0L);
        comment.setReplyToUserId(replyToUserId);
        comment.setContent(content);
        comment.setLikeCount(0);
        comment.setStatus(0);
        this.save(comment);
        // 更新帖子评论计数（原子自增，避免并发丢更新）
        postService.lambdaUpdate()
                .eq(Post::getId, postId)
                .setSql("commentCount = commentCount + 1")
                .update();
        return comment.getId();
    }

    @Override
    public List<Map<String, Object>> listCommentTree(Long postId) {
        LambdaQueryWrapper<PostComment> w = new LambdaQueryWrapper<>();
        w.eq(PostComment::getPostId, postId).eq(PostComment::getParentId, 0L)
         .orderByDesc(PostComment::getCreateTime);
        List<PostComment> topComments = this.list(w);
        if (topComments.isEmpty()) return Collections.emptyList();

        Set<Long> userIds = new HashSet<>();
        topComments.forEach(c -> userIds.add(c.getUserId()));

        // 查子回复
        List<Long> topIds = topComments.stream().map(PostComment::getId).collect(Collectors.toList());
        LambdaQueryWrapper<PostComment> childW = new LambdaQueryWrapper<>();
        childW.in(PostComment::getParentId, topIds).orderByAsc(PostComment::getCreateTime);
        List<PostComment> children = this.list(childW);
        children.forEach(c -> {
            userIds.add(c.getUserId());
            if (c.getReplyToUserId() != null) userIds.add(c.getReplyToUserId());
        });

        Map<Long, List<PostComment>> childMap = children.stream()
                .collect(Collectors.groupingBy(PostComment::getParentId));

        final Map<Long, UserVO> userMap;
        if (!userIds.isEmpty()) {
            List<User> users = userService.listByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(User::getId, userService::getUserVO, (a, b) -> a));
        } else {
            userMap = new HashMap<>();
        }

        return topComments.stream().map(parent -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", parent.getId());
            m.put("userId", parent.getUserId());
            m.put("content", parent.getContent());
            m.put("likeCount", parent.getLikeCount());
            m.put("createTime", parent.getCreateTime());
            m.put("user", userMap.get(parent.getUserId()));

            List<PostComment> childList = childMap.getOrDefault(parent.getId(), Collections.emptyList());
            List<Map<String, Object>> childMaps = childList.stream().map(c -> {
                Map<String, Object> cm = new HashMap<>();
                cm.put("id", c.getId());
                cm.put("parentId", c.getParentId());
                cm.put("userId", c.getUserId());
                cm.put("content", c.getContent());
                cm.put("replyToUserId", c.getReplyToUserId());
                cm.put("createTime", c.getCreateTime());
                cm.put("user", userMap.get(c.getUserId()));
                cm.put("replyToUser", userMap.get(c.getReplyToUserId()));
                return cm;
            }).collect(Collectors.toList());
            m.put("children", childMaps);
            return m;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId, Long userId) {
        PostComment comment = this.getById(commentId);
        if (comment == null || !comment.getUserId().equals(userId))
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        this.removeById(commentId);
        // 原子递减，防止减到负数
        postService.lambdaUpdate()
                .eq(Post::getId, comment.getPostId())
                .setSql("commentCount = GREATEST(commentCount - 1, 0)")
                .update();
    }
}
