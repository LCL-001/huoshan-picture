package com.lcl.yunpicturebackend.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置
 * 注册后 @SaSpaceCheckPermission（@SaCheckPermission）系列注解才会真正生效
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    static {
        // @SaSpaceCheckPermission 是 @SaCheckPermission 的组合注解（@AliasFor），
        // Sa-Token 默认用 JDK 反射读取注解，无法识别组合注解；
        // 切换为 Spring 的合并注解语义，使 value/mode/orRole 别名与 type 元注解正确合成
        SaAnnotationStrategy.instance.getAnnotation = (element, annotationClass) ->
                AnnotatedElementUtils.getMergedAnnotation(element, annotationClass);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // SaInterceptor 默认开启注解鉴权（isAnnotation = true），auth 函数为空操作，仅做注解校验
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/picture/**", "/space/**", "/spaceUser/**", "/file/**");
    }
}
