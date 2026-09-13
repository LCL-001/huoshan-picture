package com.lcl.myaiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;

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
     * 执行具体行动并返回结果
     * <p>
     * 该方法实现智能体的行动逻辑，在think()返回true后被调用，
     * 执行具体的操作或任务。
     * </p>
     *
     * @return 行动的执行结果描述
     */
    public abstract String act();

    /**
     * 执行ReAct模式的单步操作，包含思考和行动两个阶段
     * <p>
     * 该方法重写了父类的step()方法，实现了ReAct模式的核心逻辑：
     * 1. 首先调用think()进行思考判断
     * 2. 如果think()返回false，则直接返回"思考完成 - 无需行动"
     * 3. 如果think()返回true，则调用act()执行具体行动
     * 4. 捕获并处理执行过程中的异常
     * </p>
     *
     * @return 执行结果，可能是"思考完成 - 无需行动"、行动结果或错误信息
     */
    @Override
    public String step() {
        // 每步重置分类：think() 返回 false 走 answer（最终回答），act() 走 tool（过程步）
        this.lastStepKind = "answer";
        this.lastThinkText = null;
        this.lastToolNames = new ArrayList<>();
        try {
            boolean shouldAct = this.think();
            if (!shouldAct) {
                // 如果不需要执行行动，则返回最后一条助手消息
                return getMessageList().getLast().getText();
            }
            this.lastStepKind = "tool";
            return this.act();
        } catch (Exception e) {
            // 记录异常日志
            log.error("执行错误：{}", e.getMessage());
            return "步骤执行失败：" + e.getMessage();
        }
    }
}
