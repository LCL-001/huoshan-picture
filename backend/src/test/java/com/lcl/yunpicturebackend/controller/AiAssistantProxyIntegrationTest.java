package com.lcl.yunpicturebackend.controller;

import cn.hutool.core.lang.UUID;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.user.UserLoginRequest;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.service.IUserService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T10 凭据链集成验证（docs/plan.md T10「凭据组透传」验收）。
 * <p>
 * 单测证明不了的部分在这里：真实登录产出的凭据——Sa-Token 写的 {@code satoken} Cookie——确实能被代理的
 * 取值逻辑捞到并原样转发到引擎；会话 Cookie 也必须是**原样透传**（2026-09-14 真图库冒烟暴露的坑：
 * Spring Session 的 Cookie 值是 Base64 形态，{@code session.getId()} 是解码后的 id，用后者打图库回 40100）。
 * 桩引擎是进程内 JDK HttpServer（经 {@code @DynamicPropertySource} 注入地址），
 * 因此本类不依赖外部引擎，但需要 MySQL / Redis（真实登录），按既有约定以 IntegrationTest 结尾、
 * 不进 CI/门禁，靠本地全量回归执行。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiAssistantProxyIntegrationTest {

    /** 与 UserServiceImpl 中的常量保持一致（私有常量，测试内复制键前缀） */
    private static final String IMG_CAPTCHA_KEY = "huoshantuku:auth:captcha:img:";
    private static final String RAW_PASSWORD = "password123";
    private static final String INTERNAL_API_KEY = "integration-internal-key";
    /** 浏览器真实携带的会话 Cookie 形态（Base64 编码，实测解码后才是 session id） */
    private static final String RAW_SESSION_COOKIE = "N2MyMGYwNWItYmJmMi00YzBiLWJjOGUtODE5NDdmY2JmOGIz";

    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static volatile String engineBaseUrl;
    private static HttpServer engine;
    private static volatile String receivedUri;
    private static volatile String receivedApiKey;
    private static volatile String receivedSatoken;
    private static volatile String receivedCookie;

    @Autowired
    private IUserService userService;
    @Autowired
    private AiAssistantController aiAssistantController;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private User user;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai.assistant.engine-base-url", AiAssistantProxyIntegrationTest::startEngine);
        registry.add("app.ai.assistant.internal-api-key", () -> INTERNAL_API_KEY);
    }

    /** 惰性起桩引擎：@DynamicPropertySource 的取值时机由 Spring 决定，起服务放在取值里最稳 */
    private static synchronized String startEngine() {
        if (engine == null) {
            try {
                engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                engine.createContext("/", AiAssistantProxyIntegrationTest::handle);
                engine.setExecutor(Executors.newCachedThreadPool());
                engine.start();
                engineBaseUrl = "http://127.0.0.1:" + engine.getAddress().getPort() + "/api";
            } catch (IOException e) {
                throw new UncheckedIOException("桩引擎启动失败", e);
            }
        }
        return engineBaseUrl;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        receivedUri = exchange.getRequestURI().toString();
        receivedApiKey = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
        receivedSatoken = exchange.getRequestHeaders().getFirst("satoken");
        receivedCookie = exchange.getRequestHeaders().getFirst("Cookie");
        // 记录完再自增，测试侧以 REQUESTS>0 作为"记录已就绪"的信号
        REQUESTS.incrementAndGet();
        byte[] body = "data:[DONE]\n\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
            out.flush();
        }
    }

    @BeforeAll
    void setUpFixture() {
        user = new User();
        user.setUserAccount("ai-proxy-" + UUID.randomUUID());
        user.setUserPassword(userService.getEncryptPassword(RAW_PASSWORD));
        user.setUserName("ai-proxy-it");
        user.setUserRole(UserConstant.DEFAULT_ROLE);
        userService.save(user);
    }

    @AfterAll
    void cleanFixture() {
        if (user != null) {
            userService.removeById(user.getId());
        }
        if (engine != null) {
            engine.stop(0);
        }
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        REQUESTS.set(0);
    }

    @Test
    void forwardsCredentialsThatRealLoginActuallyProduced() {
        Cookie satokenCookie = login();
        MockHttpServletRequest loginRequest = currentRequest();
        String decodedSessionId = loginRequest.getSession(false).getId();
        assertThat(RAW_SESSION_COOKIE)
                .as("桩用的 Cookie 值必须是不同于 session id 的另一种形态，否则测不出'原样转发'")
                .isNotEqualTo(decodedSessionId);

        // 模拟浏览器后续请求：凭据唯一来源就是这两个 Cookie
        MockHttpServletRequest chatRequest = new MockHttpServletRequest();
        chatRequest.setSession(loginRequest.getSession(false));
        chatRequest.setCookies(satokenCookie, new Cookie("SESSION", RAW_SESSION_COOKIE));

        SseEmitter emitter = aiAssistantController.chat("我有几个空间", "chat-it", chatRequest);

        assertThat(emitter).isNotNull();
        awaitEngineRequest();
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(receivedUri).contains("userId=" + user.getId()).contains("chatId=chat-it");
        assertThat(receivedApiKey).isEqualTo(INTERNAL_API_KEY);
        assertThat(receivedSatoken).isEqualTo(satokenCookie.getValue());
        assertThat(receivedCookie).isEqualTo("SESSION=" + RAW_SESSION_COOKIE);
    }

    /** 只带 satoken、不带会话 Cookie：代理入口即拒，不把请求打到引擎（plan T10 负向验收） */
    @Test
    void rejectsWhenBrowserRequestCarriesOnlySatokenWithoutSessionCookie() {
        Cookie satokenCookie = login();

        MockHttpServletRequest chatRequest = new MockHttpServletRequest();
        chatRequest.setSession(currentRequest().getSession(false));
        chatRequest.setCookies(satokenCookie);

        assertThatThrownBy(() -> aiAssistantController.chat("我有几个空间", null, chatRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少会话 Cookie")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        assertThat(REQUESTS.get()).as("凭据不全必须在代理入口拦住，不能打到引擎").isZero();
    }

    /** 未登录（没有 Spring Session 用户）：先被登录门槛拦住 */
    @Test
    void rejectsWhenNotLoggedIn() {
        MockHttpServletRequest chatRequest = new MockHttpServletRequest();
        chatRequest.setSession(new MockHttpSession());
        chatRequest.setCookies(new Cookie("SESSION", RAW_SESSION_COOKIE));
        chatRequest.addHeader("satoken", "whatever-token");

        assertThatThrownBy(() -> aiAssistantController.chat("我有几个空间", null, chatRequest))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        assertThat(REQUESTS.get()).isZero();
    }

    /** 走真实登录流程（种子图形验证码绕过图形校验，频控保持开启），返回框架写出的 satoken Cookie */
    private Cookie login() {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(loginRequest, loginResponse));
        String captchaUuid = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForValue().set(IMG_CAPTCHA_KEY + captchaUuid, "abcd", 2, TimeUnit.MINUTES);
        UserLoginRequest loginRequestDto = new UserLoginRequest();
        loginRequestDto.setUserAccount(user.getUserAccount());
        loginRequestDto.setUserPassword(RAW_PASSWORD);
        loginRequestDto.setCaptchaUuid(captchaUuid);
        loginRequestDto.setCaptchaCode("abcd");
        userService.login(loginRequestDto, loginRequest);

        Cookie satokenCookie = loginResponse.getCookie("satoken");
        assertThat(satokenCookie)
                .as("登录后框架应写出名为 satoken 的 Cookie——代理读的就是这个名字")
                .isNotNull();
        return satokenCookie;
    }

    private MockHttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return (MockHttpServletRequest) attributes.getRequest();
    }

    private void awaitEngineRequest() {
        long deadline = System.currentTimeMillis() + 5000;
        while (REQUESTS.get() == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待桩引擎收到请求被中断", e);
            }
        }
    }
}
