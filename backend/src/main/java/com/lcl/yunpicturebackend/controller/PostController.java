package com.lcl.yunpicturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.domain.dto.post.PostAddRequest;
import com.lcl.yunpicturebackend.domain.dto.post.PostEditRequest;
import com.lcl.yunpicturebackend.domain.dto.post.PostQueryRequest;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PostVO;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.service.IPostService;
import com.lcl.yunpicturebackend.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@Api(tags = "论坛帖子接口")
//@RestController
@RequestMapping("/post")
@Slf4j
public class PostController {

    @Resource
    private IPostService postService;

    @Resource
    private IUserService userService;

    @ApiOperation("发布帖子")
    @PostMapping("/publish")
    public BaseResponse<Long> publishPost(@RequestBody PostAddRequest request,
                                          HttpServletRequest httpRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpRequest);
        long id = postService.publishPost(request, loginUser.getId());
        return ResultUtils.success(id);
    }

    @ApiOperation("帖子列表（滚动分页）")
    @PostMapping("/list/page")
    public BaseResponse<Page<PostVO>> listPostByPage(@RequestBody PostQueryRequest request,
                                                      HttpServletRequest httpRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.getPostVOPage(request, httpRequest));
    }

    @ApiOperation("帖子详情")
    @GetMapping("/get/{id}")
    public BaseResponse<PostVO> getPostById(@PathVariable Long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.getPostVOById(id, request));
    }

    @ApiOperation("编辑帖子")
    @PostMapping("/edit")
    public BaseResponse<Boolean> editPost(@RequestBody PostEditRequest request,
                                          HttpServletRequest httpRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpRequest);
        postService.editPost(request, loginUser.getId());
        return ResultUtils.success(true);
    }

    @ApiOperation("删除帖子")
    @PostMapping("/delete")
    public BaseResponse<Boolean> deletePost(@RequestBody DeleteRequest request,
                                            HttpServletRequest httpRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpRequest);
        postService.deletePost(request.getId(), loginUser.getId());
        return ResultUtils.success(true);
    }
}
