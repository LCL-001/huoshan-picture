package com.lcl.myaiagent.controller;

import com.lcl.myaiagent.common.BaseResponse;
import com.lcl.myaiagent.common.ErrorCode;
import com.lcl.myaiagent.common.ResultUtils;
import com.lcl.myaiagent.model.dto.user.UserLoginRequest;
import com.lcl.myaiagent.model.dto.user.UserRegisterRequest;
import com.lcl.myaiagent.model.vo.LoginUserVO;
import com.lcl.myaiagent.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @PostMapping("/register")
    public BaseResponse<String> userRegister(@RequestBody UserRegisterRequest request) {
        String userId = userService.userRegister(
                request.getUsername(), request.getPassword(), request.getCheckPassword());
        return ResultUtils.success(userId);
    }

    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest request,
                                                HttpServletRequest httpRequest) {
        LoginUserVO vo = userService.userLogin(
                request.getUsername(), request.getPassword(), httpRequest);
        return ResultUtils.success(vo);
    }

    @PostMapping("/logout")
    public BaseResponse<?> userLogout(HttpServletRequest request) {
        userService.userLogout(request);
        return ResultUtils.success(null);
    }

    @GetMapping("/current")
    public BaseResponse<LoginUserVO> getCurrentUser(HttpServletRequest request) {
        var user = userService.getLoginUser(request);
        return ResultUtils.success(userService.getLoginUserVO(user));
    }
}
