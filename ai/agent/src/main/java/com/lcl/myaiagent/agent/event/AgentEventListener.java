package com.lcl.myaiagent.agent.event;

/**
 * 事件消费者：循环只认这个接口，不关心消费端是 SSE 流还是测试里的收集器（T8-hard 抽出的缝）。
 * <p>
 * 实现约定：{@code onEvent} 不得抛异常（{@link SseAgentEventListener} 把写失败转成"客户端已断开"
 * 回调），且不得阻塞——循环在单次对话线程内同步调用它。
 */
public interface AgentEventListener {

    void onEvent(AgentEvent event);
}
