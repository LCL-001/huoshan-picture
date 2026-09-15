package com.lcl.myaiagent.agent;

import cn.hutool.core.util.StrUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 图库助手会话身份（T8 档 1）：把外部用户标识映射成引擎侧会话归属。
 * <p>
 * 会话 id 用"归属 + chatId"的确定性 UUID 派生，而不是直接拼接：
 * ① 拼接后最长可达 77 字符，超 conversation.id / chat_message.conversation_id 的 varchar(64)；
 * ② 分布式 UUID 天然按图库用户隔离——调用方即便拿到别人的 chatId，也算不出同一个会话 id，
 * 读不到别人的记忆。
 * </p>
 * <p>
 * T17 起这里还承载**调用者角色**（见 {@link #isAdmin(String)}）：角色决定本次会话挂不挂看图打标工具，
 * 与"归属"同属"这次调用是谁"的一部分。
 * </p>
 */
public final class HuoshanAssistantSession {

    /** 外部标识前缀：图库用户与引擎自身用户 id 不会互相串台 */
    public static final String OWNER_PREFIX = "huoshan:";

    /** 调用者角色请求头（图库代理透传，与 satoken 同走服务间密钥那条信道） */
    public static final String USER_ROLE_HEADER = "X-User-Role";

    /** 与图库 {@code UserConstant.ADMIN_ROLE} 同值：看图为管理员能力（T17） */
    public static final String ADMIN_ROLE = "admin";

    private static final Pattern EXTERNAL_USER_ID = Pattern.compile("\\d{1,32}");

    private HuoshanAssistantSession() {
    }

    /**
     * 调用者是否管理员（T17）。
     * <p>
     * **fail-closed**：头缺失、空串、未知值一律按普通用户处理——少挂一个工具，而不是默认多给一个。
     * 之所以只认正面匹配：这个头由图库代理由服务间密钥那条信道透传，引擎校验不了它的真伪
     * （能作假的调用方本来也持有服务间密钥，而该工具只读、且以调用者自己的凭据打图库 API，
     * 能力边界仍由图库服务端判 RBAC），所以这里不做"猜"。
     * </p>
     */
    public static boolean isAdmin(String role) {
        return ADMIN_ROLE.equalsIgnoreCase(StrUtil.trimToEmpty(role));
    }

    /**
     * 外部用户标识 → 会话归属（形如 {@code huoshan:123}）。
     *
     * @param externalUserId 图库 backend 透传的用户 id（数字字符串）
     */
    public static String owner(String externalUserId) {
        String normalized = StrUtil.trimToEmpty(externalUserId);
        if (!EXTERNAL_USER_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("图库用户标识非法：" + externalUserId);
        }
        return OWNER_PREFIX + normalized;
    }

    /**
     * 归属 + 外部会话标识 → 引擎侧会话 id（36 字符确定性 UUID）。
     *
     * @param owner  见 {@link #owner(String)}
     * @param chatId 调用方（图库端）给的会话标识；为空表示新会话
     */
    public static String conversationId(String owner, String chatId) {
        String suffix = StrUtil.isBlank(chatId) ? UUID.randomUUID().toString() : chatId.trim();
        return UUID.nameUUIDFromBytes((owner + ":" + suffix).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
