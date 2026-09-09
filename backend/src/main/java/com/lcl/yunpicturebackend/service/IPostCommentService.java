package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.po.PostComment;

import java.util.List;
import java.util.Map;

public interface IPostCommentService extends IService<PostComment> {

    long addComment(Long postId, Long userId, Long parentId, Long replyToUserId, String content);

    List<Map<String, Object>> listCommentTree(Long postId);

    void deleteComment(Long commentId, Long userId);
}
