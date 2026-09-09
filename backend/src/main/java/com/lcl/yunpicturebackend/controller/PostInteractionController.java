package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.domain.po.Post;
import com.lcl.yunpicturebackend.service.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Api(tags = "帖子互动接口")
//@RestController
@RequestMapping("/post")
@Slf4j
public class PostInteractionController {

    @Resource
    private IPostLikeService postLikeService;

    @Resource
    private IPostCommentService postCommentService;

    @Resource
    private IUserService userService;

    @Resource
    private IPostService postService;

    @Resource
    private ISocialService socialService;

    // ========= 点赞 =========

    @Data
    static class LikeRequest implements Serializable {
        private Long postId;
        private static final long serialVersionUID = 1L;
    }

    @ApiOperation("点赞/取消点赞")
    @PostMapping("/like")
    public BaseResponse<Boolean> toggleLike(@RequestBody LikeRequest req, HttpServletRequest request) {
        ThrowUtils.throwIf(req == null || req.getPostId() == null, ErrorCode.PARAMS_ERROR);
        User u = userService.getLoginUser(request);
        boolean liked = postLikeService.toggleLike(req.getPostId(), u.getId());
        // 点赞时通知帖子作者
        if (liked) {
            Post post = postService.getById(req.getPostId());
            if (post != null && !post.getUserId().equals(u.getId())) {
                socialService.sendNotification(post.getUserId(), u.getId(), "LIKE", req.getPostId(), u.getUserName() + " 赞了你的帖子");
            }
        }
        return ResultUtils.success(liked);
    }

    @ApiOperation("点赞数")
    @GetMapping("/like/count/{postId}")
    public BaseResponse<Long> likeCount(@PathVariable Long postId) {
        return ResultUtils.success(postLikeService.getLikeCount(postId));
    }

    @ApiOperation("点赞状态")
    @GetMapping("/like/status/{postId}")
    public BaseResponse<Boolean> likeStatus(@PathVariable Long postId, HttpServletRequest request) {
        User u = userService.getLoginUser(request);
        return ResultUtils.success(postLikeService.isLiked(postId, u.getId()));
    }

    // ========= 评论 =========

    @Data
    static class CommentAddRequest implements Serializable {
        private Long postId;
        private Long parentId;
        private Long replyToUserId;
        private String content;
        private static final long serialVersionUID = 1L;
    }

    @ApiOperation("添加评论")
    @PostMapping("/comment/add")
    public BaseResponse<Long> addComment(@RequestBody CommentAddRequest req, HttpServletRequest request) {
        ThrowUtils.throwIf(req == null, ErrorCode.PARAMS_ERROR);
        User u = userService.getLoginUser(request);
        long id = postCommentService.addComment(req.getPostId(), u.getId(),
                req.getParentId(), req.getReplyToUserId(), req.getContent());
        // 通知帖子作者（一级评论）或被回复用户（子回复）
        if (req.getParentId() == null || req.getParentId() == 0) {
            Post post = postService.getById(req.getPostId());
            if (post != null && !post.getUserId().equals(u.getId())) {
                socialService.sendNotification(post.getUserId(), u.getId(), "COMMENT", req.getPostId(), u.getUserName() + " 评论了你的帖子");
            }
        } else if (req.getReplyToUserId() != null && !req.getReplyToUserId().equals(u.getId())) {
            socialService.sendNotification(req.getReplyToUserId(), u.getId(), "REPLY", req.getPostId(), u.getUserName() + " 回复了你的评论");
        }
        return ResultUtils.success(id);
    }

    @ApiOperation("评论列表（树形）")
    @GetMapping("/comment/list/{postId}")
    public BaseResponse<List<Map<String, Object>>> listComments(@PathVariable Long postId) {
        return ResultUtils.success(postCommentService.listCommentTree(postId));
    }

    @ApiOperation("删除评论")
    @PostMapping("/comment/delete")
    public BaseResponse<Boolean> deleteComment(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        Long commentId = body.get("id");
        ThrowUtils.throwIf(commentId == null, ErrorCode.PARAMS_ERROR);
        User u = userService.getLoginUser(request);
        postCommentService.deleteComment(commentId, u.getId());
        return ResultUtils.success(true);
    }
}
