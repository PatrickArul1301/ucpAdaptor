package com.accenture.UCPAdaptor;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider catalogToolCallbackProvider(CatalogSearchTool catalogSearchTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(catalogSearchTool)
                .build();
    }
}