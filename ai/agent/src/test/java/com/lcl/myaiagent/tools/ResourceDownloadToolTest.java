package com.lcl.myaiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class ResourceDownloadToolTest {

    @Test
    public void testDownloadResource() {
        ResourceDownloadTool tool = new ResourceDownloadTool();
        String url = "https://yuntuku-1423326981.cos.ap-guangzhou.myqcloud.com/public/2055491979227697153/2026-05-16_CqmAmOtWujKg.webp";
        String fileName = "2026-05-16_CqmAmOtWujKg.webp";
        String result = tool.downloadResource(url, fileName);
        assertNotNull(result);
    }
}
