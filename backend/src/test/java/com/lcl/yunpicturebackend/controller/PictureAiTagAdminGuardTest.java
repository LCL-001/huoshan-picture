package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.annotation.AuthCheck;
import com.lcl.yunpicturebackend.constant.UserConstant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 打标接口的不变量守护（T15）：两个端点**必须是管理员专属**，且路径与前端约定一致。
 * <p>
 * 与 {@code AiPathSaTokenGuardTest} 同一手法——注解掉了不会有编译错误，只有这条测试会红。
 * 打标会写公共图库的真实数据，权限一旦被摘掉是线上事故，值得一条专门的守护。
 * </p>
 */
@DisplayName("AI 打标接口的管理员守护")
class PictureAiTagAdminGuardTest {

    @Test
    @DisplayName("出建议接口：@AuthCheck(mustRole=admin) + POST /picture/ai_tag/suggest")
    void suggestEndpointIsAdminOnly() {
        assertAdminOnly("suggestAiTags", "/ai_tag/suggest");
    }

    @Test
    @DisplayName("应用接口：@AuthCheck(mustRole=admin) + POST /picture/ai_tag/apply")
    void applyEndpointIsAdminOnly() {
        assertAdminOnly("applyAiTags", "/ai_tag/apply");
    }

    private void assertAdminOnly(String methodName, String expectedPath) {
        Method method = Arrays.stream(PictureController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PictureController 上找不到方法：" + methodName));
        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
        assertThat(authCheck).as("%s 必须带 @AuthCheck（打标会写公共图库真实数据）", methodName).isNotNull();
        assertThat(authCheck.mustRole()).as("%s 只能管理员调用", methodName).isEqualTo(UserConstant.ADMIN_ROLE);
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        assertThat(postMapping).as("%s 必须是 POST", methodName).isNotNull();
        assertThat(postMapping.value()).as("%s 的路径是前后端契约", methodName).containsExactly(expectedPath);
    }
}
