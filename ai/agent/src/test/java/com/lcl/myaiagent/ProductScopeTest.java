package com.lcl.myaiagent;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductScopeTest {

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void exposesOnlyProductRoutes() {
        Set<String> routes = requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(routes).contains("/ai/manus/chat");
        assertThat(routes).noneMatch(route -> route.startsWith("/ai/love_app/"));
        assertThat(routes).noneMatch(route -> route.startsWith("/ai/research/"));
        assertThat(routes).noneMatch(route -> route.startsWith("/knowledge/"));
        assertThat(routes).noneMatch(route -> route.startsWith("/gap-analyses"));
        assertThat(routes).noneMatch(route -> route.startsWith("/study-plans"));
        assertThat(routes).noneMatch(route -> route.startsWith("/interviews"));
    }
}
