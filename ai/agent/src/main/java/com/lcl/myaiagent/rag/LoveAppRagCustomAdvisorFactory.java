package com.lcl.myaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * 恋爱应用RAG自定义Advisor工厂类
 * 用于创建带有特定过滤条件的检索增强生成顾问，支持按状态筛选知识库文档
 */
@Slf4j
public class LoveAppRagCustomAdvisorFactory {

    /**
     * 创建恋爱应用RAG自定义Advisor
     * 根据指定的状态条件构建带过滤功能的文档检索器，并封装为RetrievalAugmentationAdvisor
     * 配置相似度阈值为0.5，返回最相关的3个文档
     *
     * @param vectorStore 向量存储对象，用于文档相似度检索
     * @param status      状态过滤条件，用于筛选特定状态的文档（如单身、恋爱、已婚）
     * @return Advisor 配置完成的检索增强顾问实例
     */
    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        Filter.Expression expression = new FilterExpressionBuilder().eq("status", status).build();// 创建过滤条件

        // 构建向量存储文档检索器，配置过滤条件、相似度阈值和返回数量
        VectorStoreDocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)// 向量存储
                .filterExpression(expression)// 过滤条件
                .similarityThreshold(0.5)// 相似度阈值
                .topK(3)// 返回的文档数量
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(false)// 禁用空上下文
                        .build())
                .build();// 创建文档检索增强顾问
    }
}
