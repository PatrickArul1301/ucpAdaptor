package com.accenture.UCPAdaptor.config;

import com.accenture.UCPAdaptor.tool.CartTool;
import com.accenture.UCPAdaptor.tool.CatalogSearchTool;
import com.accenture.UCPAdaptor.tool.CheckoutTool;
import com.accenture.UCPAdaptor.tool.PaymentTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider catalogToolCallbackProvider(CatalogSearchTool catalogSearchTool) {
        return MethodToolCallbackProvider.builder().toolObjects(catalogSearchTool).build();
    }

    @Bean
    public ToolCallbackProvider cartToolCallbackProvider(CartTool cartTool) {
        return MethodToolCallbackProvider.builder().toolObjects(cartTool).build();
    }

    @Bean
    public ToolCallbackProvider checkoutToolCallbackProvider(CheckoutTool checkoutTool) {
        return MethodToolCallbackProvider.builder().toolObjects(checkoutTool).build();
    }

    @Bean
    public ToolCallbackProvider paymentToolCallbackProvider(PaymentTool paymentTool) {
        return MethodToolCallbackProvider.builder().toolObjects(paymentTool).build();
    }
}
