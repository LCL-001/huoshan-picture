package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.po.UserFollow;
import com.lcl.yunpicturebackend.domain.vo.UserVO;

import java.util.List;

public interface IUserFollowService extends IService<UserFollow> {

    boolean toggleFollow(Long followerId, Long followeeId);

    long getFollowingCount(Long userId);

    long getFollowerCount(Long userId);

    boolean isFollowing(Long followerId, Long followeeId);

    List<UserVO> listFollowing(Long userId);
}
