package com.lcl.myaiagent.advisors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;

/**
 * 重读增强Advisor
 * 通过在用户问题前添加"再次阅读"提示，增强LLM对问题的理解和注意力
 * 使用模板机制将原始问题包装成强调式提示，提高回答质量
 */
public class MyReReadingAdvisor implements BaseAdvisor {

	private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
			{re2_input_query}
			Read the question again: {re2_input_query}
			""";

	private final String re2AdviseTemplate;

	private int order = 0;

	/**
	 * 使用默认模板构造重读Advisor
	 * 默认模板会在问题前后重复显示，引导LLM重新审视问题
	 */
	public MyReReadingAdvisor() {
		this(DEFAULT_RE2_ADVISE_TEMPLATE);
	}

	/**
	 * 使用自定义模板构造重读Advisor
	 *
	 * @param re2AdviseTemplate 自定义的重读提示模板，需包含{re2_input_query}占位符
	 */
	public MyReReadingAdvisor(String re2AdviseTemplate) {
		this.re2AdviseTemplate = re2AdviseTemplate;
	}

	/**
	 * 在调用LLM之前处理请求，对用户消息进行增强
	 * 使用配置的模板将原始用户问题包装成重读提示格式
	 *
	 * @param chatClientRequest 原始聊天客户端请求对象
	 * @param advisorChain      顾问链，用于控制执行流程
	 * @return ChatClientRequest 经过用户消息增强后的新请求对象
	 */
	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
		String augmentedUserText = PromptTemplate.builder()
			.template(this.re2AdviseTemplate)
			.variables(Map.of("re2_input_query", chatClientRequest.prompt().getUserMessage().getText()))
			.build()
			.render();

		return chatClientRequest.mutate()
			.prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))
			.build();
	}

	/**
	 * 在LLM响应后进行处理
	 * 当前实现直接返回原始响应，不做额外处理
	 *
	 * @param chatClientResponse 聊天客户端响应对象
	 * @param advisorChain       顾问链，用于控制执行流程
	 * @return ChatClientResponse 响应对象，保持原样返回
	 */
	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
		return chatClientResponse;
	}

	/**
	 * 获取顾问执行顺序
	 *
	 * @return 顺序值，数值越小优先级越高
	 */
	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * 设置顾问执行顺序并返回自身，支持链式调用
	 *
	 * @param order 执行顺序值
	 * @return MyReReadingAdvisor 当前实例，支持方法链式调用
	 */
	public MyReReadingAdvisor withOrder(int order) {
		this.order = order;
		return this;
	}

}
