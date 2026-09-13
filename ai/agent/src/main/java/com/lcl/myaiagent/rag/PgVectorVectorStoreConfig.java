package com.lcl.myaiagent.rag;

// [LOCAL-ONLY-DISABLED] 全本地化改造（不使用向量数据库）暂时停用本配置类；
// 恢复时取消下面注释，并同步恢复 pom.xml 中 pgvector 相关依赖（见 LOCAL-ONLY-DISABLED 标记）
//import org.springframework.ai.embedding.EmbeddingModel;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.beans.factory.annotation.Qualifier;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
//import org.springframework.jdbc.core.JdbcTemplate;
//
//import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
//import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * PgVector向量存储配置类
 * 配置基于PostgreSQL的向量数据库，用于存储和检索文档向量
 */
//@Configuration
//@ConditionalOnProperty(value = "app.vector.enabled", havingValue = "true")
//public class PgVectorVectorStoreConfig {
//
//    /**
//     * 创建PgVector向量存储Bean
//     * 配置向量维度、距离计算方式、索引类型等参数，并自动初始化数据库表结构
//     *
//     * @param jdbcTemplate           JDBC模板对象，用于数据库操作
//     * @param dashscopeEmbeddingModel 嵌入模型，用于将文本转换为向量
//     * @return VectorStore 配置完成的PgVector向量存储实例
//     */
//    @Bean
//    public VectorStore userKnowledgeVectorStore(
//            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
//            EmbeddingModel dashscopeEmbeddingModel) {
//        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
//                .dimensions(1024)                    // 不要盲目设置
//                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
//                .indexType(HNSW)                     // Optional: defaults to HNSW
//                .initializeSchema(true)              // Optional: defaults to false
//                .schemaName("public")                // Optional: defaults to "public"
//                .vectorTableName("vector_store")     // Optional: defaults to "vector_store"
//                .maxDocumentBatchSize(10000)         // Optional: defaults to 10000
//                .build();
//        return vectorStore;
//    }
//}
