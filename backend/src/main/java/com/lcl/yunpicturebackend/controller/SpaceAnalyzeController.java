package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.domain.dto.space.analyze.*;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.vo.space.analyze.*;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.service.ISpaceAnalyzeService;
import com.lcl.yunpicturebackend.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Api(tags = "空间分析接口")
@RestController
@RequestMapping("/space/analyze")
public class SpaceAnalyzeController {

    @Resource
    private ISpaceAnalyzeService spaceAnalyzeService;

    @Resource
    private IUserService userService;

    /**
     * 获取空间使用状态
     */
    @ApiOperation("获取空间使用状态")
    @PostMapping("/usage")
    public BaseResponse<SpaceUsageAnalyzeResponse> getSpaceUsageAnalyze(@RequestBody SpaceUsageAnalyzeRequest spaceUsageAnalyzeRequest,
                                                                        HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        SpaceUsageAnalyzeResponse spaceUsageAnalyze = spaceAnalyzeService.getSpaceUsageAnalyze(spaceUsageAnalyzeRequest, loginUser);
        return ResultUtils.success(spaceUsageAnalyze);
    }

    /**
     * 获取空间分类使用状态
     */
    @ApiOperation("获取空间分类使用状态")
    @PostMapping("/category")
    public BaseResponse<Object> getSpaceCategoryAnalyze(@RequestBody SpaceCategoryAnalyzeRequest spaceCategoryAnalyzeRequest,
                                                        HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<SpaceCategoryAnalyzeResponse> responseList = spaceAnalyzeService.getSpaceCategoryAnalyze(spaceCategoryAnalyzeRequest, loginUser);
        return ResultUtils.success(responseList);
    }

    /**
     * 获取空间标签使用状态
     */
    @ApiOperation("获取空间标签使用状态")
    @PostMapping("/tag")
    public BaseResponse<List<SpaceTagAnalyzeResponse>> getSpaceTagAnalyze(@RequestBody SpaceTagAnalyzeRequest spaceTagAnalyzeRequest,
                                                                          HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<SpaceTagAnalyzeResponse> responseList = spaceAnalyzeService.getSpaceTagAnalyze(spaceTagAnalyzeRequest, loginUser);
        return ResultUtils.success(responseList);
    }

    /**
     * 获取空间大小使用状态
     */
    @ApiOperation("获取空间大小使用状态")
    @PostMapping("/size")
    public BaseResponse<List<SpaceSizeAnalyzeResponse>> getSpaceSizeAnalyze(@RequestBody SpaceSizeAnalyzeRequest spaceSizeAnalyzeRequest,
                                                                            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<SpaceSizeAnalyzeResponse> resultList = spaceAnalyzeService.getSpaceSizeAnalyze(spaceSizeAnalyzeRequest, loginUser);
        return ResultUtils.success(resultList);
    }

    /**
     * 获取空间用户使用状态
     */
    @PostMapping("/user")
    public BaseResponse<List<SpaceUserAnalyzeResponse>> getSpaceUserAnalyze(@RequestBody SpaceUserAnalyzeRequest spaceUserAnalyzeRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<SpaceUserAnalyzeResponse> resultList = spaceAnalyzeService.getSpaceUserAnalyze(spaceUserAnalyzeRequest, loginUser);
        return ResultUtils.success(resultList);
    }

    /**
     * 获取空间排行使用状态
     */
    @ApiOperation("获取空间排行使用状态")
    @PostMapping("/rank")
    public BaseResponse<List<Space>> getSpaceRankAnalyze(@RequestBody SpaceRankAnalyzeRequest spaceRankAnalyzeRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<Space> resultList = spaceAnalyzeService.getSpaceRankAnalyze(spaceRankAnalyzeRequest, loginUser);
        return ResultUtils.success(resultList);
    }


}
