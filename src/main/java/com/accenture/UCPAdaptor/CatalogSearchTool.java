package com.accenture.UCPAdaptor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CatalogSearchTool {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Product catalog ───────────────────────────────────────────────────────

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

    // ── UCP request param types ───────────────────────────────────────────────
    // UCP wraps everything under a "catalog" key:
    // { "meta": {...}, "catalog": { "query": "...", "filters": {...}, "pagination": {...} } }

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

    // The UCP spec wraps params under a "catalog" key
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogParams(
            @JsonProperty("query") String query,
            @JsonProperty("filters") Filters filters,
            @JsonProperty("pagination") Pagination pagination
    ) {}

    // ── Tool handler ──────────────────────────────────────────────────────────
    // Spring AI MCP in stateless mode passes the full JSON params object.
    // UCP sends: { "meta": {...}, "catalog": { "query": "...", ... } }
    // We accept the "catalog" node and the raw "meta" node (ignored).

    @Tool(name = "search_catalog",
            description = """
              Search the product catalog. Accepts UCP-compliant search params: \
              free-text query, category and price filters, and pagination. \
              Returns a list of matching products with prices in minor currency units (cents).
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

    // ── Core search logic ─────────────────────────────────────────────────────

    private String doSearch(String query, Filters filters, Pagination pagination) throws Exception {
        List<String> categoryFilter = new ArrayList<>();
        int priceMin = 0;
        int priceMax = Integer.MAX_VALUE;

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
        final int pMin = priceMin;
        final int pMax = priceMax;

        List<Product> allFiltered = CATALOG.stream()
                .filter(p -> terms.isEmpty()
                        || terms.stream().anyMatch(t ->
                                p.name().toLowerCase().contains(t)
                                || p.description().toLowerCase().contains(t)))
                .filter(p -> categoryFilter.isEmpty()
                        || p.categories().stream().anyMatch(c -> categoryFilter.contains(c.toLowerCase())))
                .filter(p -> p.priceMinorUnits() >= pMin && p.priceMinorUnits() <= pMax)
                .toList();

        List<Product> page = allFiltered.stream().skip(offset).limit(limit).toList();

        // Build UCP-compliant response
        ObjectNode response = mapper.createObjectNode();

        ObjectNode ucpMeta = mapper.createObjectNode();
        ucpMeta.put("capability", "dev.ucp.shopping.catalog.search");
        ucpMeta.put("version", "2026-08-25");
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
            // Return price in major units (dollars) so proxy can display it cleanly
            price.put("amount", p.priceMinorUnits() / 100.0);
            price.put("currency_code", p.currency());
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
        if (hasNext) paginationNode.put("next_cursor", String.valueOf(offset + limit));
        paginationNode.put("total", allFiltered.size());
        response.set("pagination", paginationNode);

        return mapper.writeValueAsString(response);
    }
}