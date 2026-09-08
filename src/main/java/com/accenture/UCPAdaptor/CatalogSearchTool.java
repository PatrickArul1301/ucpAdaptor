package com.accenture.UCPAdaptor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CatalogSearchTool {

    private final ObjectMapper mapper = new ObjectMapper();

    private record Product(String id, String name, String description,
                           List<String> categories, int priceMinorUnits, String currency) {}

    private static final List<Product> CATALOG = List.of(
        new Product("prod-001", "Wireless Bluetooth Headphones",
            "Premium noise-cancelling headphones with 30-hour battery life",
            List.of("electronics", "audio"), 12999, "USD"),
        new Product("prod-002", "Running Shoes",
            "Lightweight breathable running shoes with cushioned sole",
            List.of("footwear", "sports"), 8999, "USD"),
        new Product("prod-003", "Coffee Maker",
            "12-cup programmable coffee maker with built-in grinder",
            List.of("kitchen", "appliances"), 5999, "USD"),
        new Product("prod-004", "Yoga Mat",
            "Extra thick non-slip yoga mat with carrying strap",
            List.of("sports", "fitness"), 3499, "USD"),
        new Product("prod-005", "Laptop Stand",
            "Adjustable aluminum laptop stand for desk ergonomics",
            List.of("electronics", "accessories"), 4999, "USD"),
        new Product("prod-006", "Stainless Steel Water Bottle",
            "Insulated 32oz water bottle keeps drinks cold for 24 hours",
            List.of("outdoor", "accessories"), 2499, "USD")
    );

    // Input types matching the UCP catalog search spec

    public record PriceFilter(
        @JsonProperty("min") Integer min,
        @JsonProperty("max") Integer max
    ) {}

    public record Filters(
        @JsonProperty("categories") List<String> categories,
        @JsonProperty("price") PriceFilter price
    ) {}

    public record Pagination(
        @JsonProperty("limit") Integer limit,
        @JsonProperty("cursor") String cursor
    ) {}

    @Tool(name = "search_catalog",
          description = """
              Performs a search against the product catalog. Supports free-text queries \
              (matched against name and description), filtering by category (OR logic) and \
              price range (in ISO 4217 minor units, e.g. cents for USD), and cursor-based pagination.
              """)
    public String searchCatalog(
            @org.springframework.ai.tool.annotation.ToolParam(description = "Free-text search query matched against product name and description", required = false) String query,
            @org.springframework.ai.tool.annotation.ToolParam(description = "Filter criteria: categories (OR logic) and price range in minor units (e.g. cents)", required = false) Filters filters,
            @org.springframework.ai.tool.annotation.ToolParam(description = "Pagination: limit (default 10) and cursor from a previous response", required = false) Pagination pagination) {
        try {

            List<String> categoryFilter = new ArrayList<>();
            int priceMin = 0;
            int priceMax = Integer.MAX_VALUE;

            if (filters != null) {
                if (filters.categories() != null) {
                    filters.categories().forEach(c -> categoryFilter.add(c.toLowerCase()));
                }
                if (filters.price() != null) {
                    if (filters.price().min() != null) priceMin = filters.price().min();
                    if (filters.price().max() != null) priceMax = filters.price().max();
                }
            }

            int limit = 10;
            int offset = 0;
            if (pagination != null) {
                if (pagination.limit() != null) limit = Math.max(1, pagination.limit());
                if (pagination.cursor() != null && !pagination.cursor().isBlank()) {
                    try { offset = Integer.parseInt(pagination.cursor().trim()); } catch (NumberFormatException ignored) {}
                }
            }

            final String q = (query != null) ? query.toLowerCase().trim() : "";
            final int pMin = priceMin;
            final int pMax = priceMax;

            List<Product> allFiltered = CATALOG.stream()
                .filter(p -> q.isEmpty()
                    || p.name().toLowerCase().contains(q)
                    || p.description().toLowerCase().contains(q))
                .filter(p -> categoryFilter.isEmpty()
                    || p.categories().stream().anyMatch(c -> categoryFilter.contains(c.toLowerCase())))
                .filter(p -> p.priceMinorUnits() >= pMin && p.priceMinorUnits() <= pMax)
                .toList();

            List<Product> page = allFiltered.stream().skip(offset).limit(limit).toList();

            // Build UCP-compliant response
            ObjectNode response = mapper.createObjectNode();

            ObjectNode ucpMeta = mapper.createObjectNode();
            ucpMeta.put("capability", "dev.ucp.shopping.catalog.search");
            ucpMeta.put("version", "2026-04-08");
            response.set("ucp", ucpMeta);

            ArrayNode productsArray = mapper.createArrayNode();
            for (Product p : page) {
                ObjectNode prod = mapper.createObjectNode();
                prod.put("id", p.id());
                prod.put("name", p.name());
                prod.put("description", p.description());
                ArrayNode cats = mapper.createArrayNode();
                p.categories().forEach(cats::add);
                prod.set("categories", cats);
                ObjectNode price = mapper.createObjectNode();
                price.put("amount", p.priceMinorUnits());
                price.put("currency", p.currency());
                prod.set("price", price);
                ObjectNode availability = mapper.createObjectNode();
                availability.put("in_stock", true);
                prod.set("availability", availability);
                productsArray.add(prod);
            }
            response.set("products", productsArray);

            ObjectNode paginationNode = mapper.createObjectNode();
            boolean hasNext = (offset + limit) < allFiltered.size();
            paginationNode.put("has_next_page", hasNext);
            if (hasNext) paginationNode.put("cursor", String.valueOf(offset + limit));
            paginationNode.put("total_count", allFiltered.size());
            response.set("pagination", paginationNode);

            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"error\": \"Search failed: " + e.getMessage() + "\"}";
        }
    }
}