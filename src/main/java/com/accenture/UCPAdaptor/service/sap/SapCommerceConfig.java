package com.accenture.UCPAdaptor.service.sap;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sap.commerce")
@Data
public class SapCommerceConfig {
    private String baseUrl = "https://electronics.local:9002";
    private String baseSite = "electronics";
    private String storefrontUrl = "https://electronics.local:9002/yacceleratorstorefront";
    private boolean sslVerify = false;
}
