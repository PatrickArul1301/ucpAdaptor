package com.accenture.UCPAdaptor.service.sap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class SapProductService {

    private final SapCommerceClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public SapProductService(SapCommerceClient client) {
        this.client = client;
    }

    public String searchProducts(String query) {
        try {
            String json = client.get().get()
                    .uri("/products/search?query={q}&fields=FULL", query)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(json);
            JsonNode products = root.path("products");

            ArrayNode ucpProducts = mapper.createArrayNode();
            for (JsonNode p : products) {
                ucpProducts.add(toUcpProduct(p));
            }

            ObjectNode result = mapper.createObjectNode();
            result.set("products", ucpProducts);
            return mapper.writeValueAsString(result);

        } catch (RestClientException e) {
            return errorJson("OCC product search failed: " + e.getMessage());
        } catch (Exception e) {
            return errorJson("Product search error: " + e.getMessage());
        }
    }

    public String getProduct(String code) {
        try {
            String json = client.get().get()
                    .uri("/products/{code}?fields=FULL", code)
                    .retrieve()
                    .body(String.class);

            JsonNode p = mapper.readTree(json);
            ObjectNode result = mapper.createObjectNode();
            result.set("product", toUcpProduct(p));
            return mapper.writeValueAsString(result);

        } catch (RestClientException e) {
            return errorJson("OCC product fetch failed: " + e.getMessage());
        } catch (Exception e) {
            return errorJson("Product fetch error: " + e.getMessage());
        }
    }

    ObjectNode toUcpProduct(JsonNode p) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", p.path("code").asText(""));
        item.put("name", p.path("name").asText(""));
        item.put("title", p.path("name").asText(""));
        item.put("description", p.path("description").asText(""));

        JsonNode price = p.path("price");
        if (!price.isMissingNode()) {
            double dollars = price.path("value").asDouble(0);
            item.put("price", Math.round(dollars * 100));
            item.put("currency", price.path("currencyIso").asText("USD"));
        } else {
            item.put("price", 0);
            item.put("currency", "USD");
        }

        String imgUrl = extractImageUrlFromNode(p);
        if (imgUrl != null) item.put("image_url", client.absoluteUrl(imgUrl));

        JsonNode stock = p.path("stock");
        ObjectNode availability = mapper.createObjectNode();
        if (!stock.isMissingNode()) {
            String status = stock.path("stockLevelStatus").asText("inStock");
            availability.put("in_stock", !"outOfStock".equalsIgnoreCase(status));
        } else {
            availability.put("in_stock", true);
        }
        item.set("availability", availability);

        return item;
    }

    public String extractImageUrlFromNode(JsonNode product) {
        JsonNode images = product.path("images");
        if (!images.isArray()) return null;
        // Prefer PRIMARY format, fall back to first image
        for (JsonNode img : images) {
            if ("PRIMARY".equals(img.path("imageType").asText())) {
                String url = img.path("url").asText("");
                if (!url.isBlank()) return url;
            }
        }
        if (images.size() > 0) {
            String url = images.get(0).path("url").asText("");
            return url.isBlank() ? null : url;
        }
        return null;
    }

    private String errorJson(String msg) {
        try {
            ObjectNode e = mapper.createObjectNode();
            e.put("error", msg);
            return mapper.writeValueAsString(e);
        } catch (Exception ex) {
            return "{\"error\":\"" + msg + "\"}";
        }
    }
}
