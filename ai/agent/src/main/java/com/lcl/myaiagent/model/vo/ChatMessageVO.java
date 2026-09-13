package com.lcl.myaiagent.model.vo;

import lombok.Builder;
import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class ChatMessageVO {
    private String id;
    private String role;
    private String content;
    private Date createTime;

    /** 该轮回答的执行步骤（工具调用/思考过程），前端渲染为折叠条；无步骤为 null */
    private List<StepVO> steps;

    @Data
    @Builder
    public static class StepVO {
        /** 步骤名：工具名或"思考" */
        private String name;
        /** 步骤明细内容 */
        private String content;
    }
}
