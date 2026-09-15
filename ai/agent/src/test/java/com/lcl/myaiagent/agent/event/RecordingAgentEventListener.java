package com.lcl.myaiagent.agent.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 收集型事件消费者（测试替身）：把循环产出的事件按序收下来，供断言使用。
 * <p>
 * T8-hard 之前"非流式"断言靠已退役的 {@code BaseAgent.run()} 返回的字符串，现在统一走
 * "事件流 + 收集"口径（生产侧不提供非流式入口，见 docs/plan.md T6.1 口径）。
 */
public class RecordingAgentEventListener implements AgentEventListener {

    private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEvent(AgentEvent event) {
        events.add(event);
    }

    public List<AgentEvent> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /** 某类事件的出现次数（如 {@code count(AgentEvent.Done.class)}） */
    public long count(Class<? extends AgentEvent> type) {
        synchronized (events) {
            return events.stream().filter(type::isInstance).count();
        }
    }

    /** 把所有 Answer 事件的文本按出现顺序拼起来（等价于退役前 run() 返回的"结果串"） */
    public String joinedAnswers() {
        return events().stream()
                .filter(AgentEvent.Answer.class::isInstance)
                .map(AgentEvent.Answer.class::cast)
                .map(AgentEvent.Answer::content)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /** 最后一条 Answer 的文本；没有则返回 null */
    public String lastAnswer() {
        List<AgentEvent> snapshot = events();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (snapshot.get(i) instanceof AgentEvent.Answer answer) {
                return answer.content();
            }
        }
        return null;
    }
}
