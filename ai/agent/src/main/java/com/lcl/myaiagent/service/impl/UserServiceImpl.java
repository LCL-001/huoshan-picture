package com.lcl.myaiagent.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.myaiagent.common.ErrorCode;
import com.lcl.myaiagent.constant.UserConstant;
import com.lcl.myaiagent.exception.BusinessException;
import com.lcl.myaiagent.mapper.UserMapper;
import com.lcl.myaiagent.model.po.User;
import com.lcl.myaiagent.model.vo.LoginUserVO;
import com.lcl.myaiagent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Override
    public String userRegister(String username, String password, String checkPassword) {
        if (username == null || username.isBlank() || username.length() < 2) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名至少2位");
        }
        if (password == null || password.length() < 6) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码至少6位");
        }
        if (!password.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次密码不一致");
        }
        synchronized (username.intern()) {
            long count = lambdaQuery().eq(User::getUsername, username).count();
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名已存在");
            }
            User user = new User();
            user.setUsername(username);
            user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
            user.setUserRole("user");
            save(user);
            return user.getId();
        }
    }

    @Override
    public LoginUserVO userLogin(String username, String password, HttpServletRequest request) {
        if (username == null || password == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名或密码不能为空");
        }
        User user = lambdaQuery().eq(User::getUsername, username).one();
        if (user == null || !passwordMatches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名或密码错误");
        }
        // 防 Session 固定攻击：销毁旧会话，创建新会话
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, user);
        return getLoginUserVO(user);
    }

    /**
     * 密码校验：BCrypt 优先，兼容旧 MD5 格式
     */
    private boolean passwordMatches(String rawPassword, String storedHash) {
        if (storedHash.startsWith("$2a$")) {
            return BCrypt.checkpw(rawPassword, storedHash);
        }
        // 旧 MD5+salt 格式兼容
        return storedHash.equals(DigestUtil.md5Hex(UserConstant.SALT + rawPassword));
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        User user = (User) session.getAttribute(UserConstant.USER_LOGIN_STATE);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return getById(user.getId());
    }

    @Override
    public boolean isAdmin(HttpServletRequest request) {
        User user = getLoginUser(request);
        return isAdmin(user);
    }

    @Override
    public boolean isAdmin(User user) {
        return "admin".equals(user.getUserRole());
    }

    @Override
    public void userLogout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        LoginUserVO vo = new LoginUserVO();
        BeanUtils.copyProperties(user, vo, "password");
        return vo;
    }
}
