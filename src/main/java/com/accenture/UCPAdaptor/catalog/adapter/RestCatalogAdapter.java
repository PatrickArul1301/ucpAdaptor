package com.accenture.UCPAdaptor.catalog.adapter;

import com.accenture.UCPAdaptor.catalog.CatalogPort;
import com.accenture.UCPAdaptor.catalog.model.Product;
import com.accenture.UCPAdaptor.catalog.model.SearchFilter;
import com.accenture.UCPAdaptor.catalog.model.SearchResult;
import com.accenture.UCPAdaptor.config.UcpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "ucp.catalog.backend", havingValue = "rest")
public class RestCatalogAdapter implements CatalogPort {

    private final RestClient restClient;
    private final UcpProperties.Catalog.Rest restConfig;
    private final ObjectMapper mapper;

    public RestCatalogAdapter(UcpProperties props, ObjectMapper mapper) {
        this.restConfig = props.getCatalog().getRest();
        this.mapper = mapper;
        RestClient.Builder builder = RestClient.builder().baseUrl(restConfig.getProductsUrl());
        if (restConfig.getAuthHeaderName() != null && !restConfig.getAuthHeaderName().isBlank()) {
            builder.defaultHeader(restConfig.getAuthHeaderName(), restConfig.getAuthHeaderValue());
        }
        this.restClient = builder.build();
    }

    @Override
    public SearchResult search(SearchFilter filter, int limit, String cursor) {
        try {
            String json = restClient.get().retrieve().body(String.class);
            JsonNode root = mapper.readTree(json);
            JsonNode array = root.isArray() ? root : root.get("products");
            if (array == null || !array.isArray()) return new SearchResult(List.of(), 0, false, null);

            List<Product> all = new ArrayList<>();
            for (JsonNode node : array) {
                Product p = mapProduct(node);
                if (p != null) all.add(p);
            }

            List<Product> filtered = applyFilter(all, filter);
            int offset = parseOffset(cursor);
            List<Product> page = filtered.stream().skip(offset).limit(limit).toList();
            boolean hasNext = (offset + limit) < filtered.size();
            return new SearchResult(page, filtered.size(), hasNext, hasNext ? String.valueOf(offset + limit) : null);
        } catch (Exception e) {
            return new SearchResult(List.of(), 0, false, null);
        }
    }

    @Override
    public Optional<Product> getProduct(String id) {
        try {
            String json = restClient.get().uri("/" + id).retrieve().body(String.class);
            JsonNode node = mapper.readTree(json);
            return Optional.ofNullable(mapProduct(node));
        } catch (Exception e) {
            return search(new SearchFilter(null, null, null, null), 1000, null)
                .products().stream().filter(p -> p.id().equals(id)).findFirst();
        }
    }

    private Product mapProduct(JsonNode node) {
        try {
            UcpProperties.Catalog.Rest.FieldMapping fm = restConfig.getFieldMapping();
            String id = resolveField(node, fm.getId());
            String name = resolveField(node, fm.getName());
            String description = resolveField(node, fm.getDescription());
            String priceStr = resolveField(node, fm.getPrice());
            String currency = resolveField(node, fm.getCurrency());
            JsonNode categoriesNode = resolveNode(node, fm.getCategories());

            int price = 0;
            if (priceStr != null) {
                double raw = Double.parseDouble(priceStr);
                price = fm.isPriceAlreadyMinorUnits() ? (int) raw : (int) (raw * 100);
            }

            List<String> categories = new ArrayList<>();
            if (categoriesNode != null && categoriesNode.isArray()) {
                for (JsonNode c : categoriesNode) categories.add(c.asText());
            }

            return new Product(id, name, description, categories, price,
                currency != null ? currency : "USD", true);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveField(JsonNode node, String path) {
        JsonNode result = resolveNode(node, path);
        return result != null ? result.asText() : null;
    }

    private JsonNode resolveNode(JsonNode node, String path) {
        if (path == null) return null;
        String[] parts = path.split("\\.");
        JsonNode current = node;
        for (String part : parts) {
            if (current == null) return null;
            try {
                int index = Integer.parseInt(part);
                current = current.get(index);
            } catch (NumberFormatException e) {
                current = current.get(part);
            }
        }
        return current;
    }

    private List<Product> applyFilter(List<Product> products, SearchFilter filter) {
        String q = filter.query() != null ? filter.query().toLowerCase() : "";
        List<String> cats = filter.categories() != null
            ? filter.categories().stream().map(String::toLowerCase).toList() : List.of();
        int min = filter.priceMin() != null ? filter.priceMin() : 0;
        int max = filter.priceMax() != null ? filter.priceMax() : Integer.MAX_VALUE;

        return products.stream()
            .filter(p -> q.isEmpty() || p.name().toLowerCase().contains(q) || p.description().toLowerCase().contains(q))
            .filter(p -> cats.isEmpty() || p.categories().stream().anyMatch(c -> cats.contains(c.toLowerCase())))
            .filter(p -> p.priceMinorUnits() >= min && p.priceMinorUnits() <= max)
            .toList();
    }

    private int parseOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try { return Integer.parseInt(cursor.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
