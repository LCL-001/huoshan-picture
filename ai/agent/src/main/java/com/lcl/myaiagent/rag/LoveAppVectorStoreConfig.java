package com.lcl.myaiagent.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 向量存储配置类
 * 负责配置和初始化基于内存的简单向量存储，用于存储和检索文档向量
 */
// [LOCAL-ONLY-DISABLED] 旧版内存向量存储：每次启动全量重嵌入，已被全本地化改造停用；恢复时取消注释
//@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.legacy-love.enabled", havingValue = "true")
public class LoveAppVectorStoreConfig {

    private final LoveAppDocumentReader loveAppDocumentReader;

    /**
     * 创建并配置向量存储Bean
     * 使用指定的嵌入模型构建SimpleVectorStore，并在初始化时加载所有文档进行向量化存储
     *
     * @param dashscopeEmbeddingModel DashScope嵌入模型，用于将文本转换为向量表示
     * @return VectorStore 配置完成并已加载文档的向量存储实例
     */
    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)
                .build();

        // 读取并添加所有文档到向量存储中
        simpleVectorStore.add(loveAppDocumentReader.readMarkdowns());
        return simpleVectorStore;
    }
}
