package com.lcl.yunpicturebackend.manager.auth;

import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * 登录态一致性校验（T56）。
 * <p>
 * 项目同时存在两套登录凭据：Spring Session（HttpSession 里的 {@code USER_LOGIN_STATE}）与 Sa-Token 的
 * space 会话（{@link StpKit#SPACE}，空间鉴权用）。两者在登录时一起写入，但属于**独立通道**：可能缺失
 * （改密 / 登出 / 过期后 Sa-Token 会话已被撤销，而 HttpSession 仍在），也可能各自指向不同的人。
 * <p>
 * 敏感写操作（删除 / 恢复 / 彻底删除 / 换绑邮箱）必须在两套凭据都存在且**同属一人**后，才做逐资源 RBAC。否则
 * {@code @SaSpaceCheckPermission} 校验的身份（按 Sa-Token 判定）与实际执行操作的身份（按 Spring Session
 * 判定）可能不是同一个人，注解保护落空；改密后仅凭 HttpSession 也可能继续操作。
 * <p>
 * 只用于写操作：匿名可访问的读接口不适用（读路径的空间查看校验另有目标绑定的实现）。
 */
@Component
@RequiredArgsConstructor
public class LoginSessionValidator {

    private final IUserService userService;

    /**
     * 校验 Spring Session 与 Sa-Token space 会话都存在且同属一人，通过后返回登录用户。
     *
     * @param request 当前请求
     * @return 登录用户（内部按 id 重新查库，改密 / 降权后的最新信息从这里生效）
     * @throws BusinessException 未登录（40100）/ 空间登录态失效或身份不一致（40102）
     */
    public User requireConsistentLogin(HttpServletRequest request) {
        // 1. Spring Session 登录用户（getLoginUser 会按 id 重新查库并校验用户仍存在）
        User loginUser = userService.getLoginUser(request);
        requireMatchingSpaceLogin(loginUser);
        return loginUser;
    }

    /**
     * 已由 Spring Session 取得用户时复用同一校验；避免用户服务反向依赖本组件而循环注入。
     * 邮箱是账号找回渠道，绑定/换绑也必须通过有效双登录态校验（T68）。
     */
    public static void requireMatchingSpaceLogin(User loginUser) {
        // 2. Sa-Token space 会话必须有效，且登录 id 与 Spring Session 用户一致；
        //    getLoginIdDefaultNull 在会话缺失 / 已撤销时返回 null（不抛异常）
        Object spaceLoginId = StpKit.SPACE.getLoginIdDefaultNull();
        if (loginUser == null || loginUser.getId() == null || spaceLoginId == null
                || !loginUser.getId().toString().equals(spaceLoginId.toString())) {
            throw new BusinessException(ErrorCode.SPACE_NOT_LOGIN);
        }
    }
}
