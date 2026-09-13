package com.lcl.myaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.JsonReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JSON文件文档读取器
 * 从classpath下的JSON文件中读取数据并转换为Spring AI的Document对象，支持多种提取策略
 */
@Component
class MyJsonReader {
    private final Resource resource;

    /**
     * 构造JSON读取器实例
     * 自动注入classpath下的products.json文件资源
     *
     * @param resource Spring资源对象，指向要读取的JSON文件
     */
    MyJsonReader(@Value("classpath:products.json") Resource resource) {
        this.resource = resource;
    }

    /**
     * 基本用法：加载JSON文件中的所有内容为文档
     * 使用默认配置解析整个JSON文件，将所有内容转换为Document列表
     *
     * @return List<Document> 从JSON文件解析出的文档列表
     */
    List<Document> loadBasicJsonDocuments() {
        JsonReader jsonReader = new JsonReader(this.resource);
        return jsonReader.get();
    }

    /**
     * 指定字段加载：仅提取JSON中指定的字段作为文档内容
     * 通过指定字段名（如description、features），只将这些字段的内容转换为文档
     *
     * @return List<Document> 包含指定字段内容的文档列表
     */
    List<Document> loadJsonWithSpecificFields() {
        JsonReader jsonReader = new JsonReader(this.resource, "description", "features");
        return jsonReader.get();
    }

    /**
     * 使用JSON指针精确提取：通过JSON Pointer语法定位并提取特定路径的内容
     * 例如使用"/items"可以只提取JSON中items数组内的所有元素作为文档
     *
     * @return List<Document> 从指定JSON路径提取的文档列表
     */
    List<Document> loadJsonWithPointer() {
        JsonReader jsonReader = new JsonReader(this.resource);
        return jsonReader.get("/items"); // 提取 items 数组内的内容
    }
}
