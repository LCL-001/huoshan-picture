package com.lcl.yunpicturebackend.config;

import cn.dev33.satoken.annotation.SaCheckDisable;
import cn.dev33.satoken.annotation.SaCheckHttpBasic;
import cn.dev33.satoken.annotation.SaCheckHttpDigest;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaCheckSafe;
import cn.dev33.satoken.annotation.SaIgnore;
import com.lcl.yunpicturebackend.manager.auth.annotation.SaSpaceCheckPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护测试（2026-09-14 review R6）：**Sa-Token 注解只在 {@code SaTokenConfigure} 注册过的路径上才生效，
 * 未注册路径上的注解会静默失效**（比不写更危险——代码看起来有权限校验，实际一点没有）。
 * <p>
 * 两条不变量：
 * <ol>
 *   <li>{@code /ai/**} 下的控制器不得出现 Sa-Token 注解——助手端点的登录门槛在
 *       {@code AiAssistantController} 里显式判，不依赖注解（见 F11「关键设计与理由」7）；</li>
 *   <li>全仓控制器里凡用了 Sa-Token 注解的，其路径必须落在
 *       {@code SaTokenConfigure.ANNOTATION_GUARDED_PATTERNS} 覆盖范围内——把"注解写在没注册的路径上"
 *       这类静默失效变成响亮的红，而不是留到线上。</li>
 * </ol>
 */
class AiPathSaTokenGuardTest {

    private static final String CONTROLLER_PACKAGE = "com.lcl.yunpicturebackend.controller";
    private static final String AI_PATH_PREFIX = "/ai";

    /** Sa-Token 1.39.0 的全部鉴权注解（含组合注解容器），加上本仓自定义的组合注解 */
    private static final List<Class<? extends Annotation>> SA_TOKEN_ANNOTATIONS = List.of(
            SaCheckLogin.class, SaCheckRole.class, SaCheckPermission.class, SaCheckSafe.class,
            SaCheckDisable.class, SaCheckHttpBasic.class, SaCheckHttpDigest.class, SaCheckOr.class,
            SaIgnore.class, SaSpaceCheckPermission.class);

    @Test
    void controllersUnderAiPathCarryNoSaTokenAnnotation() throws Exception {
        List<Class<?>> controllers = scanControllers();
        assertThat(controllers)
                .as("扫描不到控制器说明本测试是空转的，先修扫描（包名/过滤器）再谈断言")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        int aiControllers = 0;
        for (Class<?> controller : controllers) {
            if (!isUnderAiPath(basePathOf(controller))) {
                continue;
            }
            aiControllers++;
            collectViolations(controller, violations);
        }

        assertThat(aiControllers)
                .as("必须扫到 /ai/** 下的控制器（如 AiAssistantController），否则第 1 条不变量无人守护")
                .isPositive();
        assertThat(violations)
                .as("Sa-Token 注解在未注册路径上静默失效；/ai/** 的登录门槛必须在 controller 里显式判")
                .isEmpty();
    }

    @Test
    void saTokenAnnotatedControllersLiveWithinGuardedPaths() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> controller : scanControllers()) {
            List<String> found = new ArrayList<>();
            collectViolations(controller, found);
            if (found.isEmpty()) {
                continue;
            }
            String basePath = basePathOf(controller);
            if (!isCoveredByGuardedPatterns(basePath)) {
                violations.add(controller.getSimpleName() + "（路径 " + basePath + "）" + found);
            }
        }

        assertThat(violations)
                .as("这些控制器用了 Sa-Token 注解但路径不在 SaTokenConfigure 的拦截路径内，注解不会生效")
                .isEmpty();
    }

    @Test
    void interceptionPatternsKeepAiPathOutOfAnnotationTrustZone() {
        assertThat(SaTokenConfigure.ANNOTATION_GUARDED_PATTERNS)
                .as("/ai/** 一旦进拦截路径，第 1 条不变量的前提就变了，需连同 F11 口径一起复核")
                .noneMatch(pattern -> pattern.startsWith(AI_PATH_PREFIX));
    }

    /** 反射扫控制器：只认容器里真实加载的（类注解被注释掉的停用模块不会出现） */
    private List<Class<?>> scanControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            controllers.add(Class.forName(candidate.getBeanClassName()));
        }
        return controllers;
    }

    /** 类级 @RequestMapping 的路径前缀；没写路径的（如 SocialController 的裸 @RequestMapping）视作根路径 */
    private String basePathOf(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        if (mapping == null) {
            return "";
        }
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return Arrays.stream(paths).filter(p -> !p.isEmpty()).findFirst().orElse("");
    }

    private boolean isUnderAiPath(String basePath) {
        return basePath.equals(AI_PATH_PREFIX) || basePath.startsWith(AI_PATH_PREFIX + "/");
    }

    /** 拦截路径形如 /picture/**，覆盖 "/picture" 及其所有子路径 */
    private boolean isCoveredByGuardedPatterns(String basePath) {
        for (String pattern : SaTokenConfigure.ANNOTATION_GUARDED_PATTERNS) {
            String base = pattern.endsWith("/**") ? pattern.substring(0, pattern.length() - 3) : pattern;
            if (basePath.equals(base) || basePath.startsWith(base + "/")) {
                return true;
            }
        }
        return false;
    }

    private void collectViolations(Class<?> controller, List<String> violations) {
        for (Class<? extends Annotation> annotation : SA_TOKEN_ANNOTATIONS) {
            if (AnnotatedElementUtils.hasAnnotation(controller, annotation)) {
                violations.add(controller.getSimpleName() + "（类）@" + annotation.getSimpleName());
            }
        }
        for (Method method : controller.getDeclaredMethods()) {
            for (Class<? extends Annotation> annotation : SA_TOKEN_ANNOTATIONS) {
                if (AnnotatedElementUtils.hasAnnotation(method, annotation)) {
                    violations.add(controller.getSimpleName() + "#" + method.getName()
                            + " @" + annotation.getSimpleName());
                }
            }
        }
    }
}
