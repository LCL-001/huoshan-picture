package com.lcl.yunpicturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.po.UserNotification;
import com.lcl.yunpicturebackend.domain.vo.PostVO;
import com.lcl.yunpicturebackend.service.ISocialService;
import com.lcl.yunpicturebackend.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@Api(tags = "社交动态与通知接口")
@RestController
@RequestMapping
@Slf4j
public class SocialController {

    @Resource
    private ISocialService socialService;

    @Resource
    private IUserService userService;

    @ApiOperation("获取关注动态流")
//    @GetMapping("/timeline")
    public BaseResponse<Page<PostVO>> getTimeline(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(socialService.getTimeline(loginUser.getId(), current, pageSize));
    }

    @ApiOperation("获取通知列表")
    @GetMapping("/notification/list")
    public BaseResponse<Page<UserNotification>> listNotifications(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(socialService.listNotifications(loginUser.getId(), current, pageSize));
    }

    @ApiOperation("获取未读通知数")
    @GetMapping("/notification/unread")
    public BaseResponse<Long> getUnreadCount(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(socialService.getUnreadCount(loginUser.getId()));
    }

    @ApiOperation("标记通知为已读")
    @PostMapping("/notification/read/{id}")
    public BaseResponse<Boolean> markAsRead(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        socialService.markAsRead(id, loginUser.getId());
        return ResultUtils.success(true);
    }

    @ApiOperation("删除通知")
    @PostMapping("/notification/delete/{id}")
    public BaseResponse<Boolean> deleteNotification(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        socialService.deleteNotification(id, loginUser.getId());
        return ResultUtils.success(true);
    }

    @ApiOperation("清空所有通知")
    @PostMapping("/notification/clear")
    public BaseResponse<Boolean> clearNotifications(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        socialService.clearNotifications(loginUser.getId());
        return ResultUtils.success(true);
    }

    @ApiOperation("清除未读标记")
    @PostMapping("/notification/clear-unread")
    public BaseResponse<Boolean> clearUnread(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        socialService.clearUnread(loginUser.getId());
        return ResultUtils.success(true);
    }
}
