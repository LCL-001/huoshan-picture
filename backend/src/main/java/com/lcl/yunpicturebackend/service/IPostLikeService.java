package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.po.PostLike;

public interface IPostLikeService extends IService<PostLike> {

    boolean toggleLike(Long postId, Long userId);

    long getLikeCount(Long postId);

    boolean isLiked(Long postId, Long userId);
}
