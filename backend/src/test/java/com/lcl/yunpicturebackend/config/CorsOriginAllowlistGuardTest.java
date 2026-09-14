package com.lcl.yunpicturebackend.config;

import com.lcl.yunpicturebackend.manager.websocket.WebSocketConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护测试（2026-09-14 review：apex CORS 收口）。两条不变量：
 * <ol>
 *   <li><b>入库的默认来源白名单不得信任 apex</b>（{@code lincode.online}）——它与 {@code www.lincode.online}
 *       属于同一个 site（SameSite 按 eTLD+1 判定），而本套跨站防护唯一的屏障是"跨域读不到响应体"这道
 *       CORS 读限制；白名单放行 apex 等于把这道屏障对它让开（前提是 apex 上出现非本应用的可控页面）。
 *       因此凡出现 {@code lincode.online} 的条目必须带 {@code www.} 前缀；</li>
 *   <li><b>两个消费者只认一份配置</b>——HTTP 侧（{@link CorsConfig}）与 WebSocket 握手侧
 *       （{@link WebSocketConfig}）都从 {@code app.cors.allowed-origins} 取。原先这两处各手写一份列表，
 *       正是 apex 那次漂移的成因（WebSocket 握手不受 CORS 约束，那张列表就是它唯一的来源防线）。</li>
 * </ol>
 * 边界：生产那份 {@code application-prod.yaml} 按仓规不入库（密钥不入库，仓内只有
 * {@code application-local.yaml.example}），无法在此断言——本测试守护的是入库的默认值
 * （{@code application.yaml}），prod 侧靠部署时按同口径核对（见 docs/decisions.md 2026-09-14）。
 */
class CorsOriginAllowlistGuardTest {

    private static final String ORIGINS_PROPERTY = "app.cors.allowed-origins";
    /** 不带子域的裸域（apex） */
    private static final String APEX = "lincode.online";
    /** 线上实际入口：唯一允许出现在白名单里的 lincode.online 形态 */
    private static final String WWW = "www.lincode.online";

    @Test
    void trackedOriginAllowlistDoesNotTrustApexHost() throws IOException {
        List<String> origins = trackedAllowedOrigins();

        assertThat(origins)
                .as("白名单读不出来说明本测试是空转的，先修读取再谈断言")
                .isNotEmpty();
        assertThat(origins)
                .as("线上实际入口应当仍在白名单里（本次只删 apex，不是关掉线上）")
                .contains("https://" + WWW);

        List<String> mentionsDomain = origins.stream().filter(origin -> origin.contains(APEX)).toList();
        assertThat(mentionsDomain)
                .as("凡 %s 出现都必须带 www.（apex 与 www 同站，放行 apex 等于让\"跨域读不到响应体\""
                        + "这道防线失效；要放开先读 docs/decisions.md 2026-09-14）", APEX)
                .allSatisfy(origin -> assertThat(origin).contains("//" + WWW));
    }

    @Test
    void httpAndWebSocketOriginsComeFromTheSameProperty() {
        assertThat(propertyExpressions(CorsConfig.class))
                .as("CorsConfig 的来源本就从 %s 取，这里列出来是为了证明两处确实是同一个键", ORIGINS_PROPERTY)
                .anySatisfy(expression -> assertThat(expression).contains(ORIGINS_PROPERTY));

        assertThat(propertyExpressions(WebSocketConfig.class))
                .as("WebSocket 握手来源必须也从 %s 取；不要再手写一份列表——两处手写正是 apex 漂移的成因"
                        + "（WS 握手不受 CORS 约束，这张列表是它唯一的来源防线）", ORIGINS_PROPERTY)
                .anySatisfy(expression -> assertThat(expression).contains(ORIGINS_PROPERTY));
    }

    /** 读入库的默认配置（{@code application.yaml}）：与运行 profile 无关，断的就是仓里那份值 */
    private List<String> trackedAllowedOrigins() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        assertThat(sources).as("类路径上读不到 application.yaml").isNotEmpty();

        List<String> origins = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(ORIGINS_PROPERTY);
            if (value != null) {
                origins.addAll(Arrays.stream(value.toString().split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList());
            }
        }
        return origins;
    }

    /** 类上所有 @Value 表达式：用来断言"来源清单确实来自那条配置"，而不是一份字面量 */
    private List<String> propertyExpressions(Class<?> type) {
        List<String> expressions = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            Value value = field.getAnnotation(Value.class);
            if (value != null) {
                expressions.add(value.value());
            }
        }
        return expressions;
    }
}
