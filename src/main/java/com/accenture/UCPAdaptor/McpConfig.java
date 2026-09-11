package com.accenture.UCPAdaptor;

import com.accenture.UCPAdaptor.tools.CartTool;
import com.accenture.UCPAdaptor.tools.CatalogSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public ToolCallbackProvider catalogToolCallbackProvider(CatalogSearchTool catalogSearchTool, CartTool cartTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(catalogSearchTool, cartTool)
                .build();
    }
}
