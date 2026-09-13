package com.lcl.myaiagent.advisors;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限校验 Advisor
 * 功能：
 * 1. 从请求上下文中提取用户标识或 Token
 * 2. 验证用户是否有权限使用 AI 对话功能
 * 3. 支持黑白名单、配额限制等策略
 * 4. 未授权时直接返回错误响应，不调用 LLM
 */
@Slf4j
public class AuthAdvisor implements CallAdvisor, StreamAdvisor {

	private static final String AUTH_TOKEN_KEY = "auth_token";
	private static final String USER_ID_KEY = "user_id";

	// 模拟的用户数据库（实际项目中应该从数据库或缓存读取）
	private static final Set<String> ALLOWED_USERS = Set.of(
			"user_001",
			"user_002",
			"vip_user_001"
	);

	// 模拟的 Token 验证（实际应该使用 JWT 或其他认证方式）
	private static final Map<String, String> TOKEN_TO_USER = Map.of(
			"token_valid_001", "user_001",
			"token_valid_002", "user_002",
			"token_vip_001", "vip_user_001"
	);

	/**
	 * 获取顾问名称
	 *
	 * @return 顾问名称字符串
	 */
	@Override
	public String getName() {
		return "AuthAdvisor";
	}

	/**
	 * 获取顾问执行顺序
	 * 设置为最高优先级，确保在其他 Advisor 之前执行权限校验
	 *
	 * @return 顺序值，Integer.MIN_VALUE 表示最高优先级
	 */
	@Override
	public int getOrder() {
		// 设置为最高优先级，确保在其他 Advisor 之前执行
		return Integer.MIN_VALUE;
	}

	/**
	 * 处理同步调用请求的权限校验
	 * 在执行实际LLM调用前进行身份验证和权限检查，未通过则返回错误响应
	 *
	 * @param chatClientRequest 聊天客户端请求对象，包含认证信息和对话内容
	 * @param callAdvisorChain  调用顾问链，用于继续执行后续顾问或实际调用
	 * @return ChatClientResponse 权限校验通过时返回LLM响应，否则返回错误响应
	 */
	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		// 1. 提取并验证用户身份
		AuthResult authResult = authenticate(chatClientRequest);

		if (!authResult.isAuthenticated()) {
			log.warn("权限校验失败: {}", authResult.getErrorMessage());
			return createErrorResponse(authResult.getErrorMessage());
		}

		// 2. 记录授权成功日志
		log.info("权限校验成功 - 用户ID: {}, 角色: {}",
				authResult.getUserId(), authResult.getRole());

		// 3. 将用户信息添加到请求上下文，供后续使用
		ChatClientRequest authorizedRequest = enrichRequestWithContext(
				chatClientRequest, authResult);

		// 4. 继续执行后续 Advisor 和 LLM 调用
		return callAdvisorChain.nextCall(authorizedRequest);
	}

	/**
	 * 处理流式调用请求的权限校验
	 * 与同步调用类似，在流式响应前进行完整的身份验证和权限检查
	 *
	 * @param chatClientRequest  聊天客户端请求对象，包含认证信息和对话内容
	 * @param streamAdvisorChain 流式调用顾问链，用于继续执行后续顾问或实际流式调用
	 * @return Flux<ChatClientResponse> 权限校验通过时返回流式响应，否则返回包含错误的单元素流
	 */
	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
	                                             StreamAdvisorChain streamAdvisorChain) {
		// 流式响应同样需要权限校验
		AuthResult authResult = authenticate(chatClientRequest);

		if (!authResult.isAuthenticated()) {
			log.warn("流式请求权限校验失败: {}", authResult.getErrorMessage());
			return Flux.just(createErrorResponse(authResult.getErrorMessage()));
		}

		log.info("流式请求权限校验成功 - 用户ID: {}", authResult.getUserId());

		ChatClientRequest authorizedRequest = enrichRequestWithContext(
				chatClientRequest, authResult);

		return streamAdvisorChain.nextStream(authorizedRequest);
	}

	/**
	 * 核心认证逻辑
	 * 依次执行Token提取、Token验证、用户权限检查和配额检查
	 *
	 * @param request 聊天客户端请求对象
	 * @return AuthResult 认证结果，包含认证成功状态、用户ID、角色或错误信息
	 */
	private AuthResult authenticate(ChatClientRequest request) {
		try {
			// 方式1: 从请求上下文中获取 Token
			String token = extractToken(request);

			if (token == null || token.isEmpty()) {
				return AuthResult.failed("缺少认证 Token，请在请求上下文中提供 auth_token");
			}

			// 方式2: 验证 Token 并获取用户ID
			String userId = validateToken(token);

			if (userId == null) {
				return AuthResult.failed("无效的 Token，请检查认证信息");
			}

			// 方式3: 检查用户是否在白名单中
			if (!isUserAllowed(userId)) {
				return AuthResult.failed("用户 " + userId + " 没有访问权限");
			}

			// 方式4: 检查用户配额（可选）
			if (!checkQuota(userId)) {
				return AuthResult.failed("用户配额已用完，请升级套餐或联系管理员");
			}

			// 认证成功
			String role = determineUserRole(userId);
			return AuthResult.success(userId, role);

		} catch (Exception e) {
			log.error("权限校验异常", e);
			return AuthResult.failed("权限校验失败: " + e.getMessage());
		}
	}

	/**
	 * 从请求上下文中提取认证Token
	 * 优先从请求的advisors上下文中获取，也可扩展为从HTTP Header获取
	 *
	 * @param request 聊天客户端请求对象
	 * @return String 提取到的Token字符串，未找到则返回null
	 */
	private String extractToken(ChatClientRequest request) {
		// 从 advisors 参数中获取 Token
		Object tokenObj = request.context().get(AUTH_TOKEN_KEY);
		if (tokenObj != null) {
			return tokenObj.toString();
		}

		// 也可以从其他地方获取，比如 HTTP Header（如果是 Web 环境）
		// HttpServletRequest httpRequest = ...
		// return httpRequest.getHeader("Authorization");

		return null;
	}

	/**
	 * 验证Token有效性并返回关联的用户ID
	 * 当前使用Map模拟，实际项目应使用JWT解析或Redis验证
	 *
	 * @param token 待验证的认证Token字符串
	 * @return String 验证成功后返回用户ID，Token无效则返回null
	 */
	private String validateToken(String token) {
		// 简单实现：从 Map 中查找
		// 实际项目应该使用 JWT 解析、Redis 验证等方式
		return TOKEN_TO_USER.get(token);
	}

	/**
	 * 检查用户是否具有访问权限
	 * 可实现复杂的权限逻辑，如查询用户状态、封禁状态、订阅状态等
	 *
	 * @param userId 用户ID
	 * @return boolean 用户有权限返回true，否则返回false
	 */
	private boolean isUserAllowed(String userId) {
		// 可以实现复杂的权限逻辑：
		// 1. 查询数据库用户状态
		// 2. 检查用户是否被封禁
		// 3. 检查用户订阅状态
		return ALLOWED_USERS.contains(userId);
	}

	/**
	 * 检查用户使用配额是否充足
	 * 可从Redis或数据库查询今日对话次数、本月Token使用量、剩余配额等
	 *
	 * @param userId 用户ID
	 * @return boolean 配额充足返回true，配额用尽返回false
	 */
	private boolean checkQuota(String userId) {
		// 实际项目中可以从 Redis 或数据库查询：
		// - 今日对话次数
		// - 本月 Token 使用量
		// - 剩余配额

		// 这里简单返回 true
		return true;
	}

	/**
	 * 根据用户ID确定用户角色
	 * 当前根据用户ID前缀判断，可扩展为从数据库查询
	 *
	 * @param userId 用户ID
	 * @return String 用户角色，VIP用户返回"VIP"，普通用户返回"NORMAL"
	 */
	private String determineUserRole(String userId) {
		if (userId.startsWith("vip_")) {
			return "VIP";
		}
		return "NORMAL";
	}

	/**
	 * 创建权限错误响应
	 * 构造包含错误信息的AssistantMessage并封装为ChatClientResponse
	 *
	 * @param errorMessage 错误描述信息
	 * @return ChatClientResponse 包含权限错误提示的响应对象
	 */
	private ChatClientResponse createErrorResponse(String errorMessage) {
		AssistantMessage errorMessageObj = new AssistantMessage(
				"【权限错误】" + errorMessage + "\n\n请联系管理员获取访问权限。"
		);

		Generation generation = new Generation(errorMessageObj);
		ChatResponse chatResponse = new ChatResponse(
				List.of(generation),
				ChatResponseMetadata.builder().build()
		);

		return new ChatClientResponse(chatResponse, Map.of());
	}

	/**
	 * 丰富请求上下文，添加用户认证信息
	 * 可在System Prompt中注入用户角色等信息，供后续Advisor或LLM使用
	 *
	 * @param originalRequest 原始请求对象
	 * @param authResult      认证结果，包含用户ID和角色信息
	 * @return ChatClientRequest 增强后的请求对象
	 */
	private ChatClientRequest enrichRequestWithContext(
			ChatClientRequest originalRequest, AuthResult authResult) {

		// 可以在这里添加用户信息到上下文，供后续 Advisor 或 LLM 使用
		// 例如：在 System Prompt 中注入用户角色信息

		return originalRequest;
	}

	/**
	 * 认证结果封装类
	 * 用于传递认证成功状态、用户ID、角色和错误信息
	 */
	@Data
	private static class AuthResult {
		private final boolean authenticated;
		private final String userId;
		private final String role;
		private final String errorMessage;

		private AuthResult(boolean authenticated, String userId, String role, String errorMessage) {
			this.authenticated = authenticated;
			this.userId = userId;
			this.role = role;
			this.errorMessage = errorMessage;
		}

		/**
		 * 创建成功的认证结果
		 *
		 * @param userId 用户ID
		 * @param role   用户角色
		 * @return AuthResult 认证成功的结果对象
		 */
		public static AuthResult success(String userId, String role) {
			return new AuthResult(true, userId, role, null);
		}

		/**
		 * 创建失败的认证结果
		 *
		 * @param errorMessage 失败原因描述
		 * @return AuthResult 认证失败的结果对象
		 */
		public static AuthResult failed(String errorMessage) {
			return new AuthResult(false, null, null, errorMessage);
		}
	}
}
