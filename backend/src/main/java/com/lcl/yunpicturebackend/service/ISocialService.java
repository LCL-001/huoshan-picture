package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.domain.po.UserNotification;
import com.lcl.yunpicturebackend.domain.vo.PostVO;

public interface ISocialService {

    void pushToFollowersTimeline(Long userId, Long postId);

    Page<PostVO> getTimeline(Long userId, int current, int pageSize);

    void sendNotification(Long userId, Long fromUserId, String type, Long targetId, String summary);

    Page<UserNotification> listNotifications(Long userId, int current, int pageSize);

    long getUnreadCount(Long userId);

    void markAsRead(Long notificationId, Long userId);

    void deleteNotification(Long notificationId, Long userId);

    void clearNotifications(Long userId);

    void clearUnread(Long userId);
}
