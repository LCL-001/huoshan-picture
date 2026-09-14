package com.lcl.yunpicturebackend.manager.ai;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * AI 助手一次性凭据（R5，2026-09-14）：证明"发起方读得到我们的响应体"。
 * <p>
 * 为什么需要它：{@code GET /ai/assistant/chat} 有真实副作用（跑一次 agent、花 LLM 额度，档 3 起还会写数据），
 * 却只能靠 Cookie 认人。跨站顶层导航能带齐两把 Cookie（SameSite=Lax 挡的是子资源请求与跨站 POST，
 * 不挡顶层导航的 GET），攻击者因此能盲打——他读不到响应体，但服务端副作用已经发生。
 * 票把这层关系反过来用：**能读到响应体的页面才拿得到票**，攻击者拿不到，请求就在入口被拒。
 * </p>
 * <p>
 * 票不是图库凭据（不是 satoken、也不是会话标识），只在"取票 → 立刻建流"这个间隙有效：
 * 取用即删（单次使用）、60 秒过期，不入 MySQL、不进日志。
 * </p>
 */
@Slf4j
@Component
public class AiAssistantTicketManager {

    /** 键前缀沿用库内命名：huoshantuku:&lt;域&gt;:&lt;物&gt;: */
    static final String TICKET_KEY_PREFIX = "huoshantuku:ai:assistant:ticket:";

    /**
     * 票的有效期：前端取票后立刻建流，60 秒是宽松上界。
     * 短 TTL 的意义是**把泄漏窗口压到最小**——票会随 URL 进访问日志，而它必须在被记录前就失效。
     */
    static final Duration TICKET_TTL = Duration.ofSeconds(60);

    /**
     * 原子"取用即删"的 Lua 脚本（GET + DEL 的原子版本）。
     * <p>
     * 为什么不用 {@code ValueOperations.getAndDelete}：它底层下发 Redis 6.2 才有的 {@code GETDEL}，
     * 而本机开发环境实测是 5.0.14.1——集成测试直接报 {@code ERR unknown command 'GETDEL'}。
     * 脚本形式与库内解锁脚本（{@code PictureServiceImpl.UNLOCK_SCRIPT}）同一口径，不依赖 Redis 版本。
     */
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local value = redis.call('get', KEYS[1]) "
                    + "if value then redis.call('del', KEYS[1]) end "
                    + "return value",
            String.class);

    private final StringRedisTemplate stringRedisTemplate;

    public AiAssistantTicketManager(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 签发一张票：值为签发用户 id，用于校验"用票的人与取票的人是同一个" */
    public String issue(Long userId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(TICKET_KEY_PREFIX + ticket, String.valueOf(userId), TICKET_TTL);
        return ticket;
    }

    /**
     * 取用一张票（原子取用即删，天然单次使用）：返回票的签发用户 id。
     * <p>
     * 票不存在、已被用过、值非法（键值只由 {@link #issue} 写入，出现非法值说明 Redis 里被外部改过）
     * 一律返回 null，由调用方按"票无效"拒绝——这里不区分几种失败，避免给探测者任何信号。
     */
    public Long consume(String ticket) {
        if (StrUtil.isBlank(ticket)) {
            return null;
        }
        String issuedTo = stringRedisTemplate.execute(CONSUME_SCRIPT,
                Collections.singletonList(TICKET_KEY_PREFIX + ticket));
        if (StrUtil.isBlank(issuedTo)) {
            return null;
        }
        try {
            return Long.valueOf(issuedTo.trim());
        } catch (NumberFormatException e) {
            log.warn("AI 助手一次性凭据的值非法，按无效票处理");
            return null;
        }
    }
}
