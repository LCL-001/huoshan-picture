package com.lcl.myaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown文档读取器
 * 从classpath的document目录下批量读取所有.md文件，并将其解析为Spring AI的Document对象
 */
@Component
@Slf4j
public class LoveAppDocumentReader {

    private final ResourcePatternResolver resourcePatternResolver;

    /**
     * 构造Markdown文档读取器实例
     *
     * @param resourcePatternResolver Spring资源模式解析器，用于加载classpath下的文件资源
     */
    public LoveAppDocumentReader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 读取classpath:document目录下所有markdown文件并转换为文档列表
     * 遍历所有.md文件，使用MarkdownDocumentReader进行解析，并为每个文档添加文件名元数据
     * 配置选项：启用水平线分割文档、排除引用块和代码块内容
     *
     * @return List<Document> 解析后的所有文档列表，如果发生IO异常则返回已读取的部分文档
     */
    public List<Document> readMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            // 获取所有 markdown 文件的路径
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                assert filename != null;
                String status = filename.substring(filename.length() - 6, filename.length() - 4);
                // 配置Markdown解析器：添加文件名元数据、启用水平线分割、排除引用块和代码块
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withAdditionalMetadata("filename", filename)// 添加文件名元数据
                        .withHorizontalRuleCreateDocument(true)// 启用水平线分割文档
                        .withIncludeBlockquote(false)// 排除引用块
                        .withIncludeCodeBlock(false)// 排除代码块
                        .withAdditionalMetadata("status", status)// 添加标签元数据
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(reader.get());
            }
        } catch (IOException e) {
            log.error("读取 markdown 文件失败", e);
        }
        return allDocuments;
    }
}
