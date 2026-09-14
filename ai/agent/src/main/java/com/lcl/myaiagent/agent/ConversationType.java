package com.lcl.myaiagent.agent;

/**
 * 会话类型（T8 档 1）：决定工具注册表与系统提示词走哪一套。
 * <p>
 * 普通会话（NORMAL）＝引擎自带 MyManus，工具是文件/终端/搜索那套；
 * 图库助手（HUOSHAN_ASSISTANT）＝图库工具集 + 图库业务提示词，不挂文件/终端类工具。
 * code 复用既有 conversation.type 语义（varchar(20)）。
 * </p>
 */
public enum ConversationType {

    NORMAL("manus"),

    HUOSHAN_ASSISTANT("huoshan-assistant");

    private final String code;

    ConversationType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
