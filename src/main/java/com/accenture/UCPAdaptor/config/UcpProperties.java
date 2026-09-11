package com.accenture.UCPAdaptor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ucp")
@Data
public class UcpProperties {

    private String storeName = "UCP Demo Store";
    private String baseUrl;

    private String jwkKid;
    private String jwkKty;
    private String jwkCrv;
    private String jwkX;
    private String jwkY;
    private String jwkUse;
    private String jwkAlg;

    private Catalog catalog = new Catalog();

    @Data
    public static class Catalog {
        private String backend = "mock";
        private Rest rest = new Rest();

        @Data
        public static class Rest {
            private String productsUrl;
            private String authHeaderName;
            private String authHeaderValue;
            private FieldMapping fieldMapping = new FieldMapping();

            @Data
            public static class FieldMapping {
                private String id = "id";
                private String name = "name";
                private String description = "description";
                private String price = "price";
                private String currency = "currency";
                private String categories = "categories";
                private boolean priceAlreadyMinorUnits = true;
            }
        }
    }
}
