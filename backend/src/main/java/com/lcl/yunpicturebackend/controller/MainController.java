package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
@Api(tags = "健康检查接口")
public class MainController {

    /**
     * 健康检查
     */
    @ApiOperation("健康检查")
    @GetMapping("/health")
    public BaseResponse<String> health() {
        return ResultUtils.success("ok");
    }
}
