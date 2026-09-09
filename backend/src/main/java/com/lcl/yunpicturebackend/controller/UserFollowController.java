package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.service.IUserFollowService;
import com.lcl.yunpicturebackend.service.ISocialService;
import com.lcl.yunpicturebackend.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.util.List;

@Api(tags = "用户关注接口")
//@RestController
@RequestMapping("/user/follow")
@Slf4j
public class UserFollowController {

    @Resource
    private IUserFollowService userFollowService;

    @Resource
    private IUserService userService;

    @Resource
    private ISocialService socialService;

    @Data
    static class FollowRequest implements Serializable {
        private Long followeeId;
        private static final long serialVersionUID = 1L;
    }

    @ApiOperation("关注/取消关注")
    @PostMapping("/toggle")
    public BaseResponse<Boolean> toggleFollow(@RequestBody FollowRequest req, HttpServletRequest request) {
        ThrowUtils.throwIf(req == null || req.getFolloweeId() == null, ErrorCode.PARAMS_ERROR);
        User u = userService.getLoginUser(request);
        boolean following = userFollowService.toggleFollow(u.getId(), req.getFolloweeId());
        if (following) {
            socialService.sendNotification(req.getFolloweeId(), u.getId(), "FOLLOW", null, u.getUserName() + " 关注了你");
        }
        return ResultUtils.success(following);
    }

    @ApiOperation("关注状态")
    @GetMapping("/status/{followeeId}")
    public BaseResponse<Boolean> isFollowing(@PathVariable Long followeeId, HttpServletRequest request) {
        User u = userService.getLoginUser(request);
        return ResultUtils.success(userFollowService.isFollowing(u.getId(), followeeId));
    }

    @ApiOperation("关注数")
    @GetMapping("/following/count/{userId}")
    public BaseResponse<Long> followingCount(@PathVariable Long userId) {
        return ResultUtils.success(userFollowService.getFollowingCount(userId));
    }

    @ApiOperation("粉丝数")
    @GetMapping("/follower/count/{userId}")
    public BaseResponse<Long> followerCount(@PathVariable Long userId) {
        return ResultUtils.success(userFollowService.getFollowerCount(userId));
    }

    @ApiOperation("关注列表")
    @GetMapping("/following/list")
    public BaseResponse<List<UserVO>> followingList(HttpServletRequest request) {
        User u = userService.getLoginUser(request);
        return ResultUtils.success(userFollowService.listFollowing(u.getId()));
    }
}
