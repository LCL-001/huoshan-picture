package com.lcl.yunpicturebackend.manager.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.json.JSONUtil;
import com.lcl.yunpicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.enums.SpaceRoleEnum;
import com.lcl.yunpicturebackend.enums.SpaceTypeEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ISpaceUserService;
import com.lcl.yunpicturebackend.service.IUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

import static com.lcl.yunpicturebackend.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 自定义权限加载接口实现类
 */
@Component    // 保证此类被 SpringBoot 扫描，完成 Sa-Token 的自定义权限验证扩展 
public class StpInterfaceImpl implements StpInterface {
    @Value("${server.servlet.context-path}")
    private String contextPath;
    @Resource
    private IUserService userService;
    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;
    @Resource
    private ISpaceUserService spaceUserService;
    @Resource
    private ISpaceService spaceService;
    @Resource
    private IPictureService pictureService;

    /**
     * 权限认证 -- 检测当前登录用户是否有权限
     * @param loginId  账号id
     * @param loginType 账号类型
     * @return
     */
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 判断 loginType，仅对类型为 "space" 进行权限校验
        if (!StpKit.SPACE_TYPE.equals(loginType)) {
            return new ArrayList<>();
        }
        // 登录用户身份取自 Sa-Token 会话（登录时写入），绝不信任请求参数
        User loginUser = (User) StpKit.SPACE.getSessionByLoginId(loginId).get(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "用户未登录");
        }
        // 管理员权限，表示权限校验通过
        List<String> ADMIN_PERMISSIONS = spaceUserAuthManager.getPermissionsByRole(SpaceRoleEnum.ADMIN.getValue());
        // 获取上下文对象（仅含 id 类标量字段，空间归属与角色一律查库构造）
        SpaceUserAuthContext authContext = getAuthContextByRequest();
        // 上下文为空：公共图库操作（如上传、搜索），登录用户仅授予查看与上传权限，
        // 编辑/删除等写操作由服务层对目标资源的属主校验兜底
        if (isAllFieldsNull(authContext)) {
            return Arrays.asList(SpaceUserPermissionConstant.PICTURE_VIEW, SpaceUserPermissionConstant.PICTURE_UPLOAD);
        }
        // 有 spaceUserId：目标成员所属空间，按调用者自身在该空间的成员角色授权
        Long spaceUserId = authContext.getSpaceUserId();
        if (spaceUserId != null) {
            SpaceUser targetSpaceUser = spaceUserService.getById(spaceUserId);
            if (targetSpaceUser == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到空间用户信息");
            }
            return getPermissionsBySpace(targetSpaceUser.getSpaceId(), loginUser, ADMIN_PERMISSIONS);
        }
        // 有 pictureId：以图片落库的空间归属为准
        Long pictureId = authContext.getPictureId();
        if (pictureId != null) {
            Picture picture = pictureService.lambdaQuery()
                    .eq(Picture::getId, pictureId)
                    .select(Picture::getId, Picture::getSpaceId, Picture::getUserId)
                    .one();
            if (picture == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到图片信息");
            }
            // 公共图库：仅本人或管理员可管理，他人仅可查看
            if (picture.getSpaceId() == null) {
                if (picture.getUserId().equals(loginUser.getId()) || userService.isAdmin(loginUser)) {
                    return ADMIN_PERMISSIONS;
                }
                return Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW);
            }
            return getPermissionsBySpace(picture.getSpaceId(), loginUser, ADMIN_PERMISSIONS);
        }
        // 仅有 spaceId：按该空间授权
        Long spaceId = authContext.getSpaceId();
        if (spaceId == null) {
            return new ArrayList<>();
        }
        return getPermissionsBySpace(spaceId, loginUser, ADMIN_PERMISSIONS);
    }

    /**
     * 按空间判定调用者权限：私有空间仅属主或站点管理员；团队空间按数据库中的成员角色授权
     */
    private List<String> getPermissionsBySpace(Long spaceId, User loginUser, List<String> adminPermissions) {
        Space space = spaceService.getById(spaceId);
        if (space == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到空间信息");
        }
        if (Objects.equals(space.getSpaceType(), SpaceTypeEnum.PRIVATE.getValue())) {
            // 私有空间，仅属主或站点管理员有权限
            if (space.getUserId().equals(loginUser.getId()) || userService.isAdmin(loginUser)) {
                return adminPermissions;
            }
            return new ArrayList<>();
        }
        // 团队空间，查询调用者的成员角色并映射权限，非成员返回空权限集
        SpaceUser spaceUser = spaceUserService.lambdaQuery()
                .eq(SpaceUser::getSpaceId, spaceId)
                .eq(SpaceUser::getUserId, loginUser.getId())
                .one();
        if (spaceUser == null) {
            return new ArrayList<>();
        }
        return spaceUserAuthManager.getPermissionsByRole(spaceUser.getSpaceRole());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return new ArrayList<>();
    }

    /**
     * 从请求中获取上下文对象
     */
    private SpaceUserAuthContext getAuthContextByRequest() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String contentType = request.getHeader(Header.CONTENT_TYPE.getValue());
        SpaceUserAuthContext authRequest;
        // 兼容 get 和 post 操作
        if (ContentType.JSON.getValue().equals(contentType)) {
            String body = ServletUtil.getBody(request);
            authRequest = JSONUtil.toBean(body, SpaceUserAuthContext.class);
        } else {
            Map<String, String> paramMap = ServletUtil.getParamMap(request);
            authRequest = BeanUtil.toBean(paramMap, SpaceUserAuthContext.class);
        }
        // 根据请求路径区分 id 字段的含义
        Long id = authRequest.getId();
        if (ObjUtil.isNotNull(id)) {
            String requestUri = request.getRequestURI();
            String partUri = requestUri.replace(contextPath + "/", "");
            String moduleName = StrUtil.subBefore(partUri, "/", false);
            switch (moduleName) {
                case "picture":
                    authRequest.setPictureId(id);
                    break;
                case "spaceUser":
                    authRequest.setSpaceUserId(id);
                    break;
                case "space":
                    authRequest.setSpaceId(id);
                    break;
                default:
            }
        }
        return authRequest;
    }

    /**
     * 判断对象所有字段是否为空
     * @param object
     * @return
     */
    private boolean isAllFieldsNull(Object object) {
        if (object == null) {
            return true; // 对象本身为空
        }
        // 获取所有字段并判断是否所有字段都为空
        return Arrays.stream(ReflectUtil.getFields(object.getClass()))
                // 获取字段值
                .map(field -> ReflectUtil.getFieldValue(object, field))
                // 检查是否所有字段都为空
                .allMatch(ObjectUtil::isEmpty);
    }


}
