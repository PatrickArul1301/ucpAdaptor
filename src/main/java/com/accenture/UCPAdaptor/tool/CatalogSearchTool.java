package com.accenture.UCPAdaptor.tool;

import com.accenture.UCPAdaptor.service.ProductCatalogService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Component
public class CatalogSearchTool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductCatalogService catalogService;

    public CatalogSearchTool(ProductCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // ── UCP request param types ───────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceFilter(
            @JsonProperty("min") Integer min,
            @JsonProperty("max") Integer max
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Filters(
            @JsonProperty("categories") List<String> categories,
            @JsonProperty("price") PriceFilter price
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(
            @JsonProperty("limit") Integer limit,
            @JsonProperty("cursor") String cursor
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogParams(
            @JsonProperty("query") String query,
            @JsonProperty("filters") Filters filters,
            @JsonProperty("pagination") Pagination pagination
    ) {}

    // ── Tool handler ──────────────────────────────────────────────────────────

    @Tool(name = "search_catalog",
            description = """
              Search the Samsung Galaxy S smartphone catalog. Returns matching phones with name, \
              description (model + color), price (in USD dollars), categories, stock status, \
              product URL, and image URL. \
              To browse all Galaxy S phones leave the query field empty or omit it. \
              For keyword search use specific terms (e.g. "S25", "S24 Ultra", "Navy", "256GB") \
              — generic phrases like "all products" will return no results. \
              Supports category filters (e.g. "Mobile Phone", "Galaxy S25 FE", "Galaxy S26") \
              and price range in USD dollars (e.g. min:500 max:1200).
              """)
    public String searchCatalog(
            @ToolParam(description = "UCP catalog search params: {query, filters: {categories, price: {min, max}}, pagination: {limit, cursor}}", required = false)
            CatalogParams catalog) {
        try {
            return doSearch(
                    catalog != null ? catalog.query() : null,
                    catalog != null ? catalog.filters() : null,
                    catalog != null ? catalog.pagination() : null
            );
        } catch (Exception e) {
            return "{\"error\": \"Search failed: " + e.getMessage() + "\"}";
        }
    }

    // ── SKU detail lookup ─────────────────────────────────────────────────────

    private static final String PRODUCT_DETAIL_URL =
            "https://p1-smu-api-cdn.shop.samsung.com/tokocommercewebservices/v2/us/products/%s?fields=FULL";

    @Tool(name = "get_product_details",
            description = """
              Fetch full details for a specific product by its SKU / product code. \
              Use this after search_catalog when the customer wants more information \
              about a particular product — it returns complete specs, pricing, images, \
              colour variants, and availability from the live Samsung catalog. \
              Input must be the exact product code shown in the search results \
              (e.g. SM-S938UZBAVZW).
              """)
    public String getProductDetails(
            @ToolParam(description = "Exact product SKU or product code (e.g. SM-S938UZBAVZW)", required = true)
            String productCode) {
        try {
            String url = String.format(PRODUCT_DETAIL_URL, productCode.trim());

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("Accept", "application/json")
                    .build();

            HttpResponse<String> httpResponse =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            ObjectNode response = mapper.createObjectNode();

            ObjectNode ucpMeta = mapper.createObjectNode();
            ucpMeta.put("capability", "dev.ucp.shopping.catalog.product_details");
            ucpMeta.put("version", "2026-08-25");
            response.set("ucp", ucpMeta);

            if (httpResponse.statusCode() == 200) {
                JsonNode productData = mapper.readTree(httpResponse.body());
                response.set("product", productData);
            } else {
                response.put("error", "Product not found or service unavailable (HTTP "
                        + httpResponse.statusCode() + ")");
                response.put("product_code", productCode);
            }

            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"error\": \"Product lookup failed: " + e.getMessage() + "\"}";
        }
    }

    // ── Core search logic ─────────────────────────────────────────────────────

    private String doSearch(String query, Filters filters, Pagination pagination) throws Exception {
        List<String> categoryFilter = new ArrayList<>();
        double priceMin = 0;
        double priceMax = Double.MAX_VALUE;

        if (filters != null) {
            if (filters.categories() != null)
                filters.categories().forEach(c -> categoryFilter.add(c.toLowerCase()));
            if (filters.price() != null) {
                if (filters.price().min() != null) priceMin = filters.price().min();
                if (filters.price().max() != null) priceMax = filters.price().max();
            }
        }

        int limit  = (pagination != null && pagination.limit() != null) ? Math.max(1, pagination.limit()) : 10;
        int offset = 0;
        if (pagination != null && pagination.cursor() != null && !pagination.cursor().isBlank()) {
            try { offset = Integer.parseInt(pagination.cursor().trim()); } catch (NumberFormatException ignored) {}
        }

        final List<String> terms = (query != null && !query.isBlank())
                ? List.of(query.toLowerCase().trim().split("\\s+"))
                : List.of();
        final double pMin = priceMin;
        final double pMax = priceMax;

        List<ProductCatalogService.Product> allProducts = catalogService.getAll();

        List<ProductCatalogService.Product> allFiltered = allProducts.stream()
                .filter(p -> terms.isEmpty()
                        || terms.stream().anyMatch(t ->
                                p.name().toLowerCase().contains(t)
                                || p.description().toLowerCase().contains(t)))
                .filter(p -> categoryFilter.isEmpty()
                        || p.categories().stream().anyMatch(c -> categoryFilter.contains(c.toLowerCase())))
                .filter(p -> p.price() >= pMin && p.price() <= pMax)
                .toList();

        // Fall back to full catalog (minus price filter) when keyword search returns nothing
        if (allFiltered.isEmpty() && !terms.isEmpty() && categoryFilter.isEmpty()) {
            allFiltered = allProducts.stream()
                    .filter(p -> p.price() >= pMin && p.price() <= pMax)
                    .toList();
        }

        List<ProductCatalogService.Product> page = allFiltered.stream().skip(offset).limit(limit).toList();

        // Build UCP-compliant response
        ObjectNode response = mapper.createObjectNode();

        ObjectNode ucpMeta = mapper.createObjectNode();
        ucpMeta.put("capability", "dev.ucp.shopping.catalog.search");
        ucpMeta.put("version", "2026-08-25");
        response.set("ucp", ucpMeta);

        ArrayNode productsArray = mapper.createArrayNode();
        for (ProductCatalogService.Product p : page) {
            ObjectNode prod = mapper.createObjectNode();
            prod.put("id", p.id());
            prod.put("name", p.name());
            prod.put("description", p.description());
            ArrayNode cats = mapper.createArrayNode();
            p.categories().forEach(cats::add);
            prod.set("categories", cats);
            ObjectNode price = mapper.createObjectNode();
            price.put("amount", p.price());
            price.put("currency_code", p.currency());
            prod.set("price", price);
            ObjectNode availability = mapper.createObjectNode();
            availability.put("in_stock", p.inStock());
            prod.set("availability", availability);
            prod.put("url", p.url());
            prod.put("image_url", p.imageUrl());
            productsArray.add(prod);
        }
        response.set("products", productsArray);

        ObjectNode paginationNode = mapper.createObjectNode();
        boolean hasNext = (offset + limit) < allFiltered.size();
        paginationNode.put("has_next_page", hasNext);
        if (hasNext) paginationNode.put("next_cursor", String.valueOf(offset + limit));
        paginationNode.put("total", allFiltered.size());
        response.set("pagination", paginationNode);

        return mapper.writeValueAsString(response);
    }
}
