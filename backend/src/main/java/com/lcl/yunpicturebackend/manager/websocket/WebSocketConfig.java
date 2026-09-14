package com.lcl.yunpicturebackend.manager.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;
import java.util.List;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private PictureEditHandler pictureEditHandler;

    @Resource
    private WsHandshakeInterceptor wsHandshakeInterceptor;

    /**
     * 握手来源白名单：与 {@code CorsConfig} 共用 {@code app.cors.allowed-origins}（生产覆盖为线上域名）。
     * <p>
     * 不在这里手写一份：2026-09-14 review 发现两处各写一份、且都含 apex（{@code lincode.online}）——
     * 它与 www 同站，白名单放行它等于让"跨域读不到响应体"这道防线失效（见 docs/decisions.md）。
     * 一份配置也就没有第二处可以漂移。
     */
    @Value("#{'${app.cors.allowed-origins}'.split(',')}")
    private List<String> allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // websocket（握手依赖 Cookie 会话认证，必须限制来源，防止跨站 WebSocket 劫持；
        // 注意 WebSocket 握手不受 CORS 约束，这张列表就是它唯一的来源防线）
        registry.addHandler(pictureEditHandler, "/ws/picture/edit")
                .addInterceptors(wsHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins.toArray(new String[0]));
    }
}
