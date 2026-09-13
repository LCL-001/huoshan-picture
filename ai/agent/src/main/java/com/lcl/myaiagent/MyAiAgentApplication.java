package com.lcl.myaiagent;

// [LOCAL-ONLY-DISABLED] pgvector 自动配置类已随依赖停用；恢复时取消下面注释并还原 @SpringBootApplication 的 exclude
//import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;

// [LOCAL-ONLY-DISABLED] 原为 @SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
@SpringBootApplication
@EnableAsync
public class MyAiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyAiAgentApplication.class, args);
	}

}
