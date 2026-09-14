package com.lcl.myaiagent.tools.huoshan;

/**
 * 图库 API 返回业务错误码（code != 0）时抛出：code / message 原样带出，
 * 供工具层转成结构化错误 JSON，用户与模型都能看懂发生了什么（T7 档 1）。
 */
public class HuoshanApiException extends RuntimeException {

    private final int code;

    public HuoshanApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
