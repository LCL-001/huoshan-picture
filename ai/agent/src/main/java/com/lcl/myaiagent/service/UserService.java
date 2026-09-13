package com.lcl.myaiagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.myaiagent.model.po.User;
import com.lcl.myaiagent.model.vo.LoginUserVO;
import jakarta.servlet.http.HttpServletRequest;

public interface UserService extends IService<User> {

    String userRegister(String username, String password, String checkPassword);

    LoginUserVO userLogin(String username, String password, HttpServletRequest request);

    User getLoginUser(HttpServletRequest request);

    boolean isAdmin(HttpServletRequest request);

    boolean isAdmin(User user);

    void userLogout(HttpServletRequest request);

    LoginUserVO getLoginUserVO(User user);
}
