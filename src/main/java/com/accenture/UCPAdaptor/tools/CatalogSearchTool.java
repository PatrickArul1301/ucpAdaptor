package com.accenture.UCPAdaptor.tools;

import com.accenture.UCPAdaptor.catalog.CatalogPort;
import com.accenture.UCPAdaptor.catalog.model.Product;
import com.accenture.UCPAdaptor.catalog.model.SearchFilter;
import com.accenture.UCPAdaptor.catalog.model.SearchResult;
import com.accenture.UCPAdaptor.ucp.UcpCapabilityContributor;
import com.accenture.UCPAdaptor.ucp.UcpVersion;
import com.accenture.UCPAdaptor.web.DemoEventEmitter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CatalogSearchTool implements UcpCapabilityContributor {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchTool.class);

    private final CatalogPort catalogPort;
    private final ObjectMapper mapper;
    private final DemoEventEmitter demoEventEmitter;

    public CatalogSearchTool(CatalogPort catalogPort, ObjectMapper mapper, DemoEventEmitter demoEventEmitter) {
        this.catalogPort = catalogPort;
        this.mapper = mapper;
        this.demoEventEmitter = demoEventEmitter;
    }

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

    @Override public String getCapabilityId() { return "dev.ucp.shopping.catalog.search"; }
    @Override public String getSpecUrl()       { return "https://ucp.dev/2026-08-25/specification/shopping/catalog/search"; }
    @Override public String getSchemaUrl()     { return "https://ucp.dev/2026-08-25/schemas/shopping/catalog_search.json"; }

    @Tool(name = "search_catalog",
          description = """
              Performs a search against the product catalog. Supports free-text queries \
              (matched against name and description), filtering by category (OR logic) and \
              price range (in ISO 4217 minor units, e.g. cents for USD), and cursor-based pagination.
              """)
    public String searchCatalog(
            @ToolParam(description = "Free-text search query matched against product name and description", required = false) String query,
            @ToolParam(description = "Filter criteria: categories (OR logic) and price range in minor units (e.g. cents)", required = false) Filters filters,
            @ToolParam(description = "Pagination: limit (default 10) and cursor from a previous response", required = false) Pagination pagination) {
        try {
            // ── STEP 5: Gemini chose to call this tool; Spring AI invoked it ──────────
            Integer priceMin = null, priceMax = null;
            List<String> categoryFilter = List.of();

            if (filters != null) {
                if (filters.categories() != null) categoryFilter = filters.categories();
                if (filters.price() != null) {
                    priceMin = filters.price().min();
                    priceMax = filters.price().max();
                }
            }
            int limit = (pagination != null && pagination.limit() != null) ? Math.max(1, pagination.limit()) : 10;
            String cursor = (pagination != null) ? pagination.cursor() : null;

            log.info("[STEP 5] Spring AI → search_catalog | query='{}' categories={} priceMin={} priceMax={} limit={} cursor={}",
                query, categoryFilter.isEmpty() ? "any" : categoryFilter,
                priceMin == null ? "any" : priceMin,
                priceMax == null ? "any" : priceMax,
                limit, cursor == null ? "start" : cursor);

            // ── STEP 6: Emit tool_use trace event to browser ─────────────────────────
            String argsJson = buildArgsJson(query, filters, pagination);
            if (demoEventEmitter.getEmitter() != null) {
                demoEventEmitter.emit("tool_use", "{\"name\":\"search_catalog\",\"arguments\":" + argsJson + "}");
                log.info("[STEP 6] SSE 'tool_use' event → browser renders search_catalog call card");
            }

            // ── STEP 7: Delegate to CatalogPort (MockCatalogAdapter) ─────────────────
            SearchFilter searchFilter = new SearchFilter(query, categoryFilter, priceMin, priceMax);
            log.info("[STEP 7] Calling CatalogPort.search() → MockCatalogAdapter");
            SearchResult result = catalogPort.search(searchFilter, limit, cursor);
            log.info("[STEP 7] CatalogPort returned {}/{} products (page size {})",
                result.products().size(), result.totalCount(), result.products().size());

            // ── STEP 8: Build UCP-compliant JSON response ─────────────────────────────
            ObjectNode response = mapper.createObjectNode();
            ObjectNode ucpMeta = mapper.createObjectNode();
            ucpMeta.put("capability", "dev.ucp.shopping.catalog.search");
            ucpMeta.put("version", UcpVersion.CURRENT);
            response.set("ucp", ucpMeta);

            ArrayNode productsArray = mapper.createArrayNode();
            for (Product p : result.products()) {
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
                availability.put("in_stock", p.inStock());
                prod.set("availability", availability);
                productsArray.add(prod);
            }
            response.set("products", productsArray);

            ObjectNode paginationNode = mapper.createObjectNode();
            paginationNode.put("has_next_page", result.hasNextPage());
            if (result.nextCursor() != null) paginationNode.put("cursor", result.nextCursor());
            paginationNode.put("total_count", result.totalCount());
            response.set("pagination", paginationNode);

            String resultJson = mapper.writeValueAsString(response);
            log.info("[STEP 8] UCP response built (capability=dev.ucp.shopping.catalog.search, version={}) — returning to Spring AI → Gemini",
                UcpVersion.CURRENT);

            if (demoEventEmitter.getEmitter() != null) {
                demoEventEmitter.emit("tool_result", "{\"name\":\"search_catalog\",\"result\":" + resultJson + "}");
                log.info("[STEP 8] SSE 'tool_result' event → browser renders search_catalog result card");
            }

            // Spring AI sends this JSON back to Gemini as the tool result (Step 9)
            log.info("[STEP 9] Returning UCP JSON to Spring AI — it will forward to Gemini for final answer");
            return resultJson;

        } catch (Exception e) {
            log.error("[STEP 7/8] search_catalog failed: {}", e.getMessage(), e);
            return "{\"error\": \"Search failed: " + e.getMessage() + "\"}";
        }
    }

    private String buildArgsJson(String query, Filters filters, Pagination pagination) {
        try {
            ObjectNode args = mapper.createObjectNode();
            if (query != null) args.put("query", query);
            if (filters != null) args.set("filters", mapper.valueToTree(filters));
            if (pagination != null) args.set("pagination", mapper.valueToTree(pagination));
            return mapper.writeValueAsString(args);
        } catch (Exception e) {
            return "{}";
        }
    }
}
