package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.user.UserEmailBindRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserLoginRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserPasswordResetRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserQueryRequest;
import com.lcl.yunpicturebackend.domain.dto.user.UserRegisterRequest;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.CaptchaVO;
import com.lcl.yunpicturebackend.domain.vo.LoginUserVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.enums.UserRoleEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.mapper.UserMapper;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.service.MailSendService;
import com.lcl.yunpicturebackend.utils.IpUtils;
import com.lcl.yunpicturebackend.utils.SqlSortUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.lcl.yunpicturebackend.constant.UserConstant.USER_LOGIN_STATE;

/**
 * <p>
 * 用户 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-04-17
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 登录失败计数 Redis key 前缀（账号维度 / IP 维度）
     */
    private static final String LOGIN_FAIL_ACCOUNT_KEY = "yupicture:auth:login:fail:account:";
    private static final String LOGIN_FAIL_IP_KEY = "yupicture:auth:login:fail:ip:";

    /**
     * 图形验证码 Redis key 前缀，TTL 2 分钟
     */
    private static final String IMG_CAPTCHA_KEY = "yupicture:auth:captcha:img:";

    /**
     * 邮箱验证码 Redis key：验证码（TTL 5 分钟）/ 错误计数（TTL 5 分钟）
     */
    private static final String EMAIL_CAPTCHA_KEY = "yupicture:auth:captcha:email:";
    private static final String EMAIL_CAPTCHA_FAIL_KEY = "yupicture:auth:captcha:email:fail:";
    /**
     * 邮箱验证码频控：同邮箱间隔 60 秒 / 每日 10 条 / 同 IP 每日 20 条
     */
    private static final String EMAIL_SEND_INTERVAL_KEY = "yupicture:auth:captcha:email:interval:";
    private static final String EMAIL_SEND_DAILY_KEY = "yupicture:auth:captcha:email:daily:";
    private static final String EMAIL_SEND_IP_DAILY_KEY = "yupicture:auth:captcha:email:ip:";
    private static final long EMAIL_SEND_INTERVAL_SECONDS = 60;
    private static final long EMAIL_SEND_DAILY_LIMIT = 10;
    private static final long EMAIL_SEND_IP_DAILY_LIMIT = 20;
    private static final long EMAIL_CAPTCHA_TTL_SECONDS = 300;
    private static final long EMAIL_CAPTCHA_FAIL_LIMIT = 5;

    @Resource
    private MailSendService mailSendService;

    /**
     * 同账号 15 分钟内最多失败 5 次，同 IP 15 分钟内最多失败 20 次
     */
    private static final long ACCOUNT_FAIL_LIMIT = 5;
    private static final long IP_FAIL_LIMIT = 20;
    private static final long FAIL_WINDOW_SECONDS = 900;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 验证码开关：登录图形验证码与注册/重置/绑定邮箱的邮箱验证码校验。
     * 默认开启；仅压测/联调（test profile）关闭，缺失该配置时按开启兜底。
     */
    @Value("${app.auth.captcha-enabled:true}")
    private boolean captchaEnabled;

    /**
     * 登录限流开关：同账号 5 次/15 分钟、同 IP 20 次/15 分钟。
     * 默认开启；仅压测（test profile）关闭。
     */
    @Value("${app.auth.login-limit-enabled:true}")
    private boolean loginLimitEnabled;


    @Override
    public BaseResponse<Long> register(UserRegisterRequest userRegisterRequest) {
        // 1. 校验
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(userRegisterRequest.getUserAccount() == null || userRegisterRequest.getUserPassword() == null || userRegisterRequest.getCheckPassword() == null, ErrorCode.PARAMS_ERROR,
                "参数为空");
        ThrowUtils.throwIf(userRegisterRequest.getUserAccount().length() < 4, ErrorCode.PARAMS_ERROR,
                "用户账号过短");
        ThrowUtils.throwIf(userRegisterRequest.getUserPassword().length() < 8, ErrorCode.PARAMS_ERROR,
                "用户密码过短");
        ThrowUtils.throwIf(!userRegisterRequest.getUserPassword().equals(userRegisterRequest.getCheckPassword()), ErrorCode.PARAMS_ERROR,
                "两次输入的密码不一致");
        // 1.1. 邮箱格式与验证码校验
        String email = userRegisterRequest.getEmail();
        ThrowUtils.throwIf(StrUtil.isBlank(email) || !Validator.isEmail(email.trim()),
                ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        verifyEmailCaptcha(email, userRegisterRequest.getEmailCode());
        String normalizedEmail = email.trim().toLowerCase();
        // 2. 校验用户是否重复（统一文案，防止枚举已注册账号）
        User user = lambdaQuery().eq(User::getUserAccount, userRegisterRequest.getUserAccount()).one();
        ThrowUtils.throwIf(user != null, ErrorCode.PARAMS_ERROR, "注册失败，请更换账号或稍后再试");
        User emailExist = lambdaQuery().eq(User::getEmail, normalizedEmail).one();
        ThrowUtils.throwIf(emailExist != null, ErrorCode.PARAMS_ERROR, "注册失败，请更换账号或稍后再试");
        // 3. 密码加密
        String encryptPassword = getEncryptPassword(userRegisterRequest.getUserPassword());
        // 4. 插入数据
        User u = new User();
        u.setUserAccount(userRegisterRequest.getUserAccount());
        u.setEmail(normalizedEmail);
        u.setUserPassword(encryptPassword);
        u.setUserName("默认用户名-" + UUID.randomUUID().toString().substring(0, 8));
        u.setUserAvatar("https://yuntuku-1423326981.cos.ap-guangzhou.myqcloud.com/public/2045047058943492098/2026-05-14_PELq4JjfWCnr.webp");
        u.setUserRole(UserRoleEnum.USER.getValue());
        boolean success;
        try {
            success = save(u);
        } catch (DuplicateKeyException e) {
            // 并发下命中 email/userAccount 唯一索引，统一文案防枚举
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "注册失败，请更换账号或稍后再试");
        }
        // 5. 返回结果
        ThrowUtils.throwIf(!success, ErrorCode.SYSTEM_ERROR, "注册失败");
        return ResultUtils.success(u.getId());
    }

    @Override
    public BaseResponse<LoginUserVO> login(UserLoginRequest userLoginRequest, HttpServletRequest request) {
        // 1. 校验
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(userLoginRequest.getUserAccount() == null || userLoginRequest.getUserPassword() == null, ErrorCode.PARAMS_ERROR,
                "参数为空");
        ThrowUtils.throwIf(userLoginRequest.getUserAccount().length() < 4, ErrorCode.PARAMS_ERROR,
                "用户账号错误");
                ThrowUtils.throwIf(userLoginRequest.getUserPassword().length() < 8, ErrorCode.PARAMS_ERROR,
                "用户密码错误");
        // 2.0. 校验图形验证码（一次性消费，防脚本爆破）
        verifyImgCaptcha(userLoginRequest.getCaptchaUuid(), userLoginRequest.getCaptchaCode());
        // 2.1. 登录限流：同账号 5 次/15 分钟、同 IP 20 次/15 分钟，防爆破
        String account = userLoginRequest.getUserAccount();
        String ip = IpUtils.getClientIp(request);
        checkLoginRateLimit(account, ip);
        // 2.2. 查询用户是否存在
        User user = lambdaQuery()
                .eq(User::getUserAccount, account)
                .one();
        if (user == null || !checkAndUpgradePassword(user, userLoginRequest.getUserPassword())) {
            // 统一文案防枚举；失败计入限流
            recordLoginFail(account, ip);
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 3. 登录成功，清除该账号的失败计数
        stringRedisTemplate.delete(LOGIN_FAIL_ACCOUNT_KEY + account);
        // 4. 记录用户的登录态（脱敏后会话对象，不存放密码等敏感字段）
        User sessionUser = new User();
        BeanUtil.copyProperties(user, sessionUser);
        sessionUser.setUserPassword(null);
        request.getSession().setAttribute(USER_LOGIN_STATE, sessionUser);
        // 4. 记录用户登录态到 Sa-token，便于空间鉴权时使用，注意保证该用户信息与 SpringSession 中的信息过期时间一致
        StpKit.SPACE.login(user.getId());
        StpKit.SPACE.getSession().set(USER_LOGIN_STATE, sessionUser);

        return ResultUtils.success(BeanUtil.copyProperties(user, LoginUserVO.class));
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 1. 判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        ThrowUtils.throwIf(currentUser == null || currentUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        // 2. 获取当前登录的用户信息
        currentUser = getById(currentUser.getId());
        ThrowUtils.throwIf(currentUser == null, ErrorCode.NOT_LOGIN_ERROR);
        // 3. 返回
        return currentUser;
    }

    /**
     * 忘记密码：邮箱验证码校验通过后重置密码
     */
    @Override
    public BaseResponse<Boolean> resetPassword(UserPasswordResetRequest userPasswordResetRequest) {
        ThrowUtils.throwIf(userPasswordResetRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.hasBlank(userPasswordResetRequest.getEmail(), userPasswordResetRequest.getEmailCode(),
                userPasswordResetRequest.getNewPassword()), ErrorCode.PARAMS_ERROR, "参数为空");
        ThrowUtils.throwIf(userPasswordResetRequest.getNewPassword().length() < 8, ErrorCode.PARAMS_ERROR,
                "新密码长度不能小于 8 位");
        // 校验邮箱验证码（一次性）
        verifyEmailCaptcha(userPasswordResetRequest.getEmail(), userPasswordResetRequest.getEmailCode());
        String normalized = userPasswordResetRequest.getEmail().trim().toLowerCase();
        User user = lambdaQuery().eq(User::getEmail, normalized).one();
        ThrowUtils.throwIf(user == null, ErrorCode.PARAMS_ERROR, "重置失败，请确认邮箱或稍后再试");
        lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getUserPassword, getEncryptPassword(userPasswordResetRequest.getNewPassword()))
                .update();
        // 密码已更换，清空该账号的登录失败计数
        stringRedisTemplate.delete(LOGIN_FAIL_ACCOUNT_KEY + user.getUserAccount());
        return ResultUtils.success(true);
    }

    /**
     * 绑定/换绑当前登录用户的邮箱（需邮箱验证码）
     */
    @Override
    public BaseResponse<Boolean> bindEmail(UserEmailBindRequest userEmailBindRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userEmailBindRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = getLoginUser(request);
        String email = userEmailBindRequest.getEmail();
        ThrowUtils.throwIf(StrUtil.isBlank(email) || !Validator.isEmail(email.trim()),
                ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        String normalized = email.trim().toLowerCase();
        // 校验邮箱验证码（一次性）
        verifyEmailCaptcha(normalized, userEmailBindRequest.getEmailCode());
        // 唯一性：被其他用户占用的邮箱不可绑定
        User exist = lambdaQuery().eq(User::getEmail, normalized).ne(User::getId, loginUser.getId()).one();
        ThrowUtils.throwIf(exist != null, ErrorCode.PARAMS_ERROR, "绑定失败，该邮箱已被使用");
        try {
            lambdaUpdate().eq(User::getId, loginUser.getId()).set(User::getEmail, normalized).update();
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "绑定失败，该邮箱已被使用");
        }
        return ResultUtils.success(true);
    }

    @Override
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        // 1. 判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        ThrowUtils.throwIf(currentUser == null || currentUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        // 2. 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        // 3. 注销 Sa-Token 登录态，保证登出后空间权限立即失效
        try {
            StpKit.SPACE.logout();
        } catch (Exception ignored) {
            // Sa-Token 中无对应登录态时忽略
        }
        // 4. 返回
        return ResultUtils.success(true);
    }

    public String getEncryptPassword(String userPassword) {
        return passwordEncoder.encode(userPassword);
    }

    @Override
    public CaptchaVO createImgCaptcha() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String uuid = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForValue().set(IMG_CAPTCHA_KEY + uuid, captcha.getCode().toLowerCase(),
                2, TimeUnit.MINUTES);
        CaptchaVO vo = new CaptchaVO();
        vo.setCaptchaUuid(uuid);
        vo.setImageBase64(captcha.getImageBase64Data());
        return vo;
    }

    @Override
    public void verifyImgCaptcha(String captchaUuid, String captchaCode) {
        // 压测/联调 profile 关闭验证码时直接放行（参数也不校验，方便脚本调用）
        if (!captchaEnabled) {
            return;
        }
        ThrowUtils.throwIf(StrUtil.hasBlank(captchaUuid, captchaCode), ErrorCode.PARAMS_ERROR, "验证码错误");
        String key = IMG_CAPTCHA_KEY + captchaUuid;
        String answer = stringRedisTemplate.opsForValue().get(key);
        // 一次性：无论对错立即删除，防重放
        stringRedisTemplate.delete(key);
        ThrowUtils.throwIf(answer == null || !answer.equalsIgnoreCase(captchaCode.trim()),
                ErrorCode.PARAMS_ERROR, "验证码错误");
    }

    @Override
    public void sendEmailCaptcha(HttpServletRequest request, String scene, String email, String captchaUuid, String captchaCode) {
        // 0. 场景校验：决定防枚举方向
        ThrowUtils.throwIf(!"register".equals(scene) && !"reset".equals(scene) && !"bind".equals(scene),
                ErrorCode.PARAMS_ERROR, "场景参数错误");
        // 1. 图形验证码前置，挡脚本刷邮件接口
        verifyImgCaptcha(captchaUuid, captchaCode);
        // 2. 邮箱格式校验
        ThrowUtils.throwIf(StrUtil.isBlank(email) || !Validator.isEmail(email.trim()),
                ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        String normalized = email.trim().toLowerCase();
        // 3. 频控：同邮箱 60 秒间隔
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(EMAIL_SEND_INTERVAL_KEY + normalized, "1", EMAIL_SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);
        ThrowUtils.throwIf(!Boolean.TRUE.equals(first), ErrorCode.OPERATION_ERROR, "发送过于频繁，请稍后再试");
        // 4. 频控：同邮箱每日上限 / 同 IP 每日上限
        Long daily = incrWithWindow(EMAIL_SEND_DAILY_KEY + normalized, TimeUnit.DAYS.toSeconds(1));
        ThrowUtils.throwIf(daily != null && daily > EMAIL_SEND_DAILY_LIMIT,
                ErrorCode.OPERATION_ERROR, "今日发送次数已达上限，请明日再试");
        String ip = IpUtils.getClientIp(request);
        Long ipDaily = incrWithWindow(EMAIL_SEND_IP_DAILY_KEY + ip, TimeUnit.DAYS.toSeconds(1));
        ThrowUtils.throwIf(ipDaily != null && ipDaily > EMAIL_SEND_IP_DAILY_LIMIT,
                ErrorCode.OPERATION_ERROR, "今日发送次数已达上限，请明日再试");
        // 5. 按场景静默跳过（响应与正常完全一致，双向防枚举）：
        //    register：已注册邮箱不发码；reset：未注册邮箱不发码；bind：任何已注册邮箱不发码
        User exist = lambdaQuery().eq(User::getEmail, normalized).one();
        boolean registered = exist != null;
        if ("register".equals(scene) && registered) {
            return;
        }
        if ("reset".equals(scene) && !registered) {
            return;
        }
        if ("bind".equals(scene) && registered) {
            return;
        }
        // 6. 生成验证码并写入 Redis（先落库后异步发信，发送失败可重取）
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(EMAIL_CAPTCHA_KEY + normalized, code,
                EMAIL_CAPTCHA_TTL_SECONDS, TimeUnit.SECONDS);
        stringRedisTemplate.delete(EMAIL_CAPTCHA_FAIL_KEY + normalized);
        mailSendService.sendCaptchaMailAsync(normalized, code);
    }

    @Override
    public void verifyEmailCaptcha(String email, String emailCode) {
        // 压测/联调 profile 关闭验证码时直接放行
        if (!captchaEnabled) {
            return;
        }
        ThrowUtils.throwIf(StrUtil.hasBlank(email, emailCode), ErrorCode.PARAMS_ERROR, "验证码错误或已过期");
        String normalized = email.trim().toLowerCase();
        String key = EMAIL_CAPTCHA_KEY + normalized;
        String answer = stringRedisTemplate.opsForValue().get(key);
        if (answer == null || !answer.equals(emailCode.trim())) {
            // 错误计数：同码累计错误 5 次作废
            Long fails = incrWithWindow(EMAIL_CAPTCHA_FAIL_KEY + normalized, EMAIL_CAPTCHA_TTL_SECONDS);
            if (fails != null && fails >= EMAIL_CAPTCHA_FAIL_LIMIT) {
                stringRedisTemplate.delete(key);
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误或已过期");
        }
        // 校验通过：验证码与错误计数一并作废（一次性）
        stringRedisTemplate.delete(key);
        stringRedisTemplate.delete(EMAIL_CAPTCHA_FAIL_KEY + normalized);
    }

    /**
     * 登录前限流检查：同账号 15 分钟内失败达 5 次、或同 IP 15 分钟内失败达 20 次则拒绝登录
     */
    private void checkLoginRateLimit(String account, String ip) {
        // 压测 profile 关闭限流时直接放行
        if (!loginLimitEnabled) {
            return;
        }
        Long accountFail = getFailCount(LOGIN_FAIL_ACCOUNT_KEY + account);
        if (accountFail != null && accountFail >= ACCOUNT_FAIL_LIMIT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "登录失败次数过多，请 15 分钟后再试");
        }
        if (ip != null) {
            Long ipFail = getFailCount(LOGIN_FAIL_IP_KEY + ip);
            if (ipFail != null && ipFail >= IP_FAIL_LIMIT) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "登录请求过于频繁，请稍后再试");
            }
        }
    }

    /**
     * 记录一次登录失败（账号与 IP 维度分别计数，首次失败启动 15 分钟窗口）
     */
    private void recordLoginFail(String account, String ip) {
        incrementFailCount(LOGIN_FAIL_ACCOUNT_KEY + account);
        if (ip != null) {
            incrementFailCount(LOGIN_FAIL_IP_KEY + ip);
        }
    }

    private Long getFailCount(String key) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            return value == null ? null : Long.parseLong(value);
        } catch (Exception e) {
            // 计数异常不阻断登录主流程，按无限流处理
            return null;
        }
    }

    private void incrementFailCount(String key) {
        incrWithWindow(key, FAIL_WINDOW_SECONDS);
    }

    /**
     * 计数器自增并维护过期窗口：首次自增设 TTL；对历史遗留的无 TTL 键补设，避免永久锁定/永久频控
     *
     * @return 自增后的计数，Redis 异常时返回 null
     */
    private Long incrWithWindow(String key, long windowSeconds) {
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count == null) {
                return null;
            }
            if (count == 1) {
                stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            } else if (stringRedisTemplate.getExpire(key) == -1) {
                // 兜底：increment 与 expire 之间异常中断会产生无 TTL 的计数
                stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            return count;
        } catch (Exception e) {
            // Redis 不可用时降级为不限流，不影响主流程
            return null;
        }
    }

    /**
     * 校验密码：优先 BCrypt；不匹配时回退校验历史 MD5 密码，命中后自动升级为 BCrypt 存储
     *
     * @param user        用户（含数据库中的密码哈希）
     * @param rawPassword 用户输入的明文密码
     * @return 校验是否通过
     */
    private boolean checkAndUpgradePassword(User user, String rawPassword) {
        String stored = user.getUserPassword();
        if (StrUtil.isBlank(stored)) {
            return false;
        }
        if (passwordEncoder.matches(rawPassword, stored)) {
            return true;
        }
        // 兼容历史 MD5 + 固定盐的密码
        if (stored.equals(getLegacyMd5Password(rawPassword))) {
            lambdaUpdate()
                    .eq(User::getId, user.getId())
                    .set(User::getUserPassword, passwordEncoder.encode(rawPassword))
                    .update();
            return true;
        }
        return false;
    }

    /**
     * 历史 MD5 + 固定盐加密算法（仅用于登录时兼容校验，新密码一律使用 BCrypt）
     */
    private String getLegacyMd5Password(String rawPassword) {
        final String salt = "asdewqzzxcc";
        return DigestUtils.md5DigestAsHex((salt + rawPassword).getBytes());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjUtil.isNotNull(id), "id", id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        // 排序（白名单校验，防止 ORDER BY 注入）
        String safeSortField = SqlSortUtils.sanitizeSortField(sortField, "id", "userAccount", "userName",
                "userProfile", "userRole", "createTime", "editTime", "updateTime");
        queryWrapper.orderBy(StrUtil.isNotEmpty(safeSortField), "ascend".equals(sortOrder), safeSortField);
        return queryWrapper;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }


}
