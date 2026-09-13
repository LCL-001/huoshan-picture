package com.lcl.myaiagent.advisors;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * 自定义日志顾问类
 * 实现CallAdvisor和StreamAdvisor接口，用于记录聊天客户端的请求和响应日志
 * 支持同步调用和流式调用的日志记录
 */
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {
	/**
	 * 获取顾问名称
	 * 使用类的简单名称作为顾问标识
	 *
	 * @return 顾问名称字符串
	 */
	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	/**
	 * 获取顾问执行顺序
	 * 返回值越小，优先级越高
	 *
	 * @return 顺序值，0表示最高优先级
	 */
	@Override
	public int getOrder() {
		return 0;
	}


	/**
	 * 处理同步调用请求的增强逻辑
	 * 在执行实际调用前后分别记录请求和响应日志
	 *
	 * @param chatClientRequest 聊天客户端请求对象，包含请求消息和配置
	 * @param callAdvisorChain  调用顾问链，用于继续执行后续顾问或实际调用
	 * @return ChatClientResponse 聊天客户端响应对象，包含AI的回复
	 */
	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		logRequest(chatClientRequest);

		ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);

		logResponse(chatClientResponse);

		return chatClientResponse;
	}

	/**
	 * 处理流式调用请求的增强逻辑
	 * 记录请求日志，并对流式响应进行聚合后记录日志
	 *
	 * @param chatClientRequest 聊天客户端请求对象，包含请求消息和配置
	 * @param streamAdvisorChain 流式调用顾问链，用于继续执行后续顾问或实际流式调用
	 * @return Flux<ChatClientResponse> 响应式流，包含聚合后的聊天客户端响应
	 */
	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
		logRequest(chatClientRequest);

		Flux<ChatClientResponse> chatClientResponses = streamAdvisorChain.nextStream(chatClientRequest);

		return new ChatClientMessageAggregator().aggregateChatClientResponse(chatClientResponses, this::logResponse);
	}

	/**
	 * 记录请求日志
	 * 使用INFO级别记录请求信息，并返回原始请求对象
	 *
	 * @param request 聊天客户端请求对象
	 * @return ChatClientRequest 原始请求对象，保持不变
	 */
	private ChatClientRequest logRequest(ChatClientRequest request) {
		log.info("request: {}", request.prompt());
		return request;
	}

	/**
	 * 记录响应日志
	 * 使用INFO级别记录响应信息
	 *
	 * @param chatClientResponse 聊天客户端响应对象，包含AI的回复内容
	 */
	private void logResponse(ChatClientResponse chatClientResponse) {
		log.info("response: {}", chatClientResponse.chatResponse().getResult().getOutput().getText());
	}

}
