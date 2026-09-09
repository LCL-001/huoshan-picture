package com.lcl.yunpicturebackend;

import org.apache.shardingsphere.spring.boot.ShardingSphereAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(exclude = {ShardingSphereAutoConfiguration.class})
@EnableAsync
@EnableAspectJAutoProxy(exposeProxy = true)
public class YunPictureBaseApplication {

    static {
        // 允许 HttpURLConnection 显式设置 Host 等受限头。
        // URL 上传的 SSRF 防护需要直连已校验的 IP 并保留原始 Host 头，JDK 默认会静默忽略 Host 头
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    public static void main(String[] args) {
        SpringApplication.run(YunPictureBaseApplication.class, args);
    }

}
