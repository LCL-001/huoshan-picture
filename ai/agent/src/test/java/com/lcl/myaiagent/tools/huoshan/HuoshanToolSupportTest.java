package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HuoshanToolSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unexpectedExceptionMessageDoesNotCrossTheUserBoundary() throws Exception {
        String result = HuoshanToolSupport.render(() -> {
            throw new IllegalStateException("connect failed: http://127.0.0.1:8123/internal-path");
        });

        JsonNode json = MAPPER.readTree(result);
        assertThat(json.get("code").asInt()).isEqualTo(-1);
        assertThat(json.get("message").asText())
                .isEqualTo("调用图库接口失败，请稍后重试")
                .doesNotContain("127.0.0.1", "internal-path");
    }
}
