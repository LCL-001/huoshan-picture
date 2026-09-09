package com.lcl.yunpicturebackend.aop;

import cn.hutool.core.bean.BeanUtil;
import com.lcl.yunpicturebackend.annotation.AuthCheck;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.LoginUserVO;
import com.lcl.yunpicturebackend.enums.UserRoleEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.service.IUserService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Objects;

@Aspect
@Component
public class AuthInterceptor {

    @Resource
    private IUserService userService;
    /**
     * 执行拦截
     * @param joinPoint 切入点
     * @param authCheck 权限校验注解
     * @return
     */
    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        // 1. 获取用户必须角色
        String mustRole = authCheck.mustRole();
        UserRoleEnum mustRoleEnum = UserRoleEnum.getEnumByValue(mustRole);
        if (mustRoleEnum == null) {
            // 不需要权限, 直接放行
            return joinPoint.proceed();
        }
        // 2. 获取当前登录用户
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = ((ServletRequestAttributes) Objects.requireNonNull(requestAttributes)).getRequest();
        User loginUser = userService.getLoginUser(request);
        LoginUserVO userVO = BeanUtil.copyProperties(loginUser, LoginUserVO.class);
        // 3. 获取当前用户角色
        String userRole = userVO.getUserRole();
        UserRoleEnum userRoleEnum = UserRoleEnum.getEnumByValue(userRole);
        // 4. 比较用户权限是否匹配
        // 没有权限，拒绝访问
        if (userRoleEnum == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 要求用户有管理员权限，但是当前用户又没有管理员权限，拒绝访问
        if (UserRoleEnum.ADMIN.equals(mustRoleEnum) && !UserRoleEnum.ADMIN.equals(userRoleEnum)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 5. 通过权限校验，放行
        return joinPoint.proceed();
    }
}
