package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.event.AgentEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ReAct智能体抽象基类，继承自BaseAgent
 * <p>
 * ReAct（Reasoning + Acting）模式结合了推理和行动两个核心能力。
 * 该类实现了think-act循环：先通过think()方法进行思考判断，
 * 再根据思考结果决定是否执行act()方法。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
public abstract class ReActAgent extends BaseAgent {

    /**
     * 思考过程，判断是否需要执行行动
     * <p>
     * 该方法实现智能体的推理逻辑，分析当前状态和上下文，
     * 决定是否需要采取具体行动。
     * </p>
     *
     * @return true表示需要执行行动，false表示无需行动
     */
    public abstract boolean think();

    /**
     * 执行具体行动并返回本步要发出的事件
     * <p>
     * 该方法实现智能体的行动逻辑，在think()返回true后被调用，
     * 执行具体的操作或任务。
     * </p>
     *
     * @return 本步产出的事件（工具步：思考 + 工具结果；以用户提问收尾的步：一条回答）
     */
    public abstract List<AgentEvent> act();

    /**
     * 执行ReAct模式的单步操作，包含思考和行动两个阶段
     * <p>
     * 该方法重写了父类的step()方法，实现了ReAct模式的核心逻辑：
     * 1. 首先调用think()进行思考判断
     * 2. 如果think()返回false，则以最后一条助手消息作为回答事件（该消息通常已由 think() 写入）
     * 3. 如果think()返回true，则调用act()执行具体行动
     * 4. 捕获并处理执行过程中的异常
     * </p>
     *
     * @return 本步产出的事件
     */
    @Override
    public List<AgentEvent> step() {
        try {
            boolean shouldAct = this.think();
            if (!shouldAct) {
                // 如果不需要执行行动，则以最后一条助手消息作为最终回答
                return List.of(new AgentEvent.Answer(getMessageList().getLast().getText()));
            }
            return this.act();
        } catch (Exception e) {
            // 记录异常日志
            log.error("执行错误：{}", e.getMessage());
            return List.of(new AgentEvent.Answer("步骤执行失败：" + e.getMessage()));
        }
    }
}
