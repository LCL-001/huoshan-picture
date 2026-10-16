package com.lcl.myaiagent.agent;

import cn.hutool.core.util.StrUtil;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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

    /**
     * 确认执行结果事实的内部提示前缀（T44）：写操作确认执行成功后，由 backend 经内部端点追加一条
     * 以 USER 角色入库的"操作结果"事实——模型续聊可见（回放保留 USER 行）。
     * <p>
     * **这只是给模型看的来源提示（T59 澄清）**：文本前缀用户也能照抄，所以它**不能**当作可信判据。
     * 前端历史是否隐藏、某行是否真是系统事实，一律看 {@link #SOURCE_SYSTEM_FACT} 那条持久化 metadata
     * （见 {@link #METADATA_SOURCE_KEY}）；不能宣称"前缀 = 防注入边界"。
     * </p>
     */
    public static final String OPERATION_RESULT_PREFIX = "【系统记录】";

    /**
     * 消息来源元数据键（T59）：写进 {@code chat_message.metadata} 列，用于区分"用户原文"与"系统事实"。
     * <p>
     * 与文本前缀不同，这个标记由服务端在写库时落下、用户无从伪造，所以它是唯一可信的判据——
     * 前端历史隐藏（{@code ChatHistoryAssembler}）与来源识别都以它为准；无此键的行是本次改造之前的旧行，
     * 只能按文本前缀兜底。
     * </p>
     */
    public static final String METADATA_SOURCE_KEY = "huoshanSource";

    /** 来源取值：用户原文（用户自己发的消息；即便照抄内部提示前缀，也不算系统事实）。 */
    public static final String SOURCE_USER = "user";

    /** 来源取值：系统事实（由 backend 内部端点写入的操作结果，用户不可伪造）。 */
    public static final String SOURCE_SYSTEM_FACT = "system_fact";

    /**
     * 操作标识类 metadata 键（T60）：系统事实行上额外记下"是哪一次写操作、什么类型、成了没有"，
     * 与来源标记同列持久化——同一会话里多张确认卡片的乱序结果在库里也彼此可辨。
     */
    public static final String METADATA_OPERATION_ID_KEY = "operationId";

    /** 操作类型键（T60）：batch_edit / url_import */
    public static final String METADATA_OPERATION_TYPE_KEY = "operationType";

    /** 操作结果键（T60）：executed / failed */
    public static final String METADATA_OUTCOME_KEY = "outcome";

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
     * 该行是否为系统事实（T59 的**可信判据**）：只看持久化 metadata，不看可被用户伪造的文本前缀。
     */
    public static boolean isSystemFact(Map<String, Object> metadata) {
        return metadata != null && SOURCE_SYSTEM_FACT.equals(metadata.get(METADATA_SOURCE_KEY));
    }

    /** 该行是否带来源标记（T59）：无标记的是本次改造之前的旧行，前端隐藏只能按文本前缀兜底。 */
    public static boolean hasSourceMarker(Map<String, Object> metadata) {
        return metadata != null && metadata.get(METADATA_SOURCE_KEY) != null;
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
