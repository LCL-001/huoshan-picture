package com.lcl.yunpicturebackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.jackson.JsonComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Spring MVC JSON 序列化配置类
 * <p>
 * 用于自定义 Jackson 的序列化行为，解决前后端交互中的数据类型转换问题。
 * 主要处理 Long 类型在 JSON 序列化时的精度丢失问题。
 */
@JsonComponent
public class JsonConfig {

    /**
     * 配置 ObjectMapper 以解决 Long 类型精度丢失问题
     * <p>
     * 当前端使用 JavaScript 接收 JSON 数据时，JavaScript 的 Number 类型只能安全表示
     * -2^53 到 2^53 之间的整数。而 Java 的 Long 类型范围更大，直接序列化为数字
     * 可能导致前端精度丢失。此配置将 Long 类型序列化为字符串格式传输。
     *
     * @param builder Jackson2ObjectMapperBuilder 构建器，由 Spring 容器自动注入
     * @return ObjectMapper 配置完成的 JSON 序列化器实例
     */
    @Bean
    public ObjectMapper jacksonObjectMapper(Jackson2ObjectMapperBuilder builder) {
        // 创建 ObjectMapper 实例，禁用 XML 支持
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();

        // 创建自定义序列化模块
        SimpleModule module = new SimpleModule();

        // 注册 Long 对象类型和基本类型的序列化器，统一转换为字符串
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);

        // 注册自定义模块到 ObjectMapper
        objectMapper.registerModule(module);
        return objectMapper;
    }
}