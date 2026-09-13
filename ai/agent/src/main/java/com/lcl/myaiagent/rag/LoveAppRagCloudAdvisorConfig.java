package com.lcl.myaiagent.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;


/**
 * 恋爱应用RAG云顾问配置类
 * 配置基于阿里云DashScope的检索增强生成(RAG) Advisor
 */
// [LOCAL-ONLY-DISABLED] DashScope 云端知识库 Advisor：全本地化改造（不使用云端知识库）暂时停用，恢复时取消注释
//@Configuration
@Slf4j
@ConditionalOnProperty(name = "app.legacy-love.enabled", havingValue = "true")
class LoveAppRagCloudAdvisorConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建恋爱应用RAG云顾问Bean
     * 该Advisor使用阿里云DashScope的文档检索功能，从"恋爱大师"知识库中检索相关文档，
     * 为聊天客户端提供检索增强生成能力
     *
     * @return Advisor 配置完成的检索增强顾问实例
     */
    @Bean
    public Advisor loveAppRagCloudAdvisor() {
        DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(dashScopeApiKey).build();
        final String KNOWLEDGE_INDEX = "恋爱大师";

        DocumentRetriever documentRetriever = new DashScopeDocumentRetriever(dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .withIndexName(KNOWLEDGE_INDEX)
                        .build());

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }
}
