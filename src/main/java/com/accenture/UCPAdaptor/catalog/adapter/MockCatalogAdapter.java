package com.accenture.UCPAdaptor.catalog.adapter;

import com.accenture.UCPAdaptor.catalog.CatalogPort;
import com.accenture.UCPAdaptor.catalog.model.Product;
import com.accenture.UCPAdaptor.catalog.model.SearchFilter;
import com.accenture.UCPAdaptor.catalog.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "ucp.catalog.backend", havingValue = "mock", matchIfMissing = true)
public class MockCatalogAdapter implements CatalogPort {

    private static final Logger log = LoggerFactory.getLogger(MockCatalogAdapter.class);

    private static final List<Product> CATALOG = List.of(
        new Product("prod-001", "Wireless Bluetooth Headphones",
            "Premium noise-cancelling headphones with 30-hour battery life",
            List.of("electronics", "audio"), 12999, "USD", true),
        new Product("prod-002", "Running Shoes",
            "Lightweight breathable running shoes with cushioned sole",
            List.of("footwear", "sports"), 8999, "USD", true),
        new Product("prod-003", "Coffee Maker",
            "12-cup programmable coffee maker with built-in grinder",
            List.of("kitchen", "appliances"), 5999, "USD", true),
        new Product("prod-004", "Yoga Mat",
            "Extra thick non-slip yoga mat with carrying strap",
            List.of("sports", "fitness"), 3499, "USD", true),
        new Product("prod-005", "Laptop Stand",
            "Adjustable aluminum laptop stand for desk ergonomics",
            List.of("electronics", "accessories"), 4999, "USD", true),
        new Product("prod-006", "Stainless Steel Water Bottle",
            "Insulated 32oz water bottle keeps drinks cold for 24 hours",
            List.of("outdoor", "accessories"), 2499, "USD", true)
    );

    @Override
    public SearchResult search(SearchFilter filter, int limit, String cursor) {
        // ── STEP 7: CatalogPort.search() dispatched here ──────────────────────────
        String q = (filter.query() != null) ? filter.query().toLowerCase().trim() : "";
        List<String> categoryFilter = (filter.categories() != null)
            ? filter.categories().stream().map(String::toLowerCase).toList()
            : List.of();
        int priceMin = (filter.priceMin() != null) ? filter.priceMin() : 0;
        int priceMax = (filter.priceMax() != null) ? filter.priceMax() : Integer.MAX_VALUE;

        int offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            try { offset = Integer.parseInt(cursor.trim()); } catch (NumberFormatException ignored) {}
        }

        log.info("[STEP 7] MockCatalogAdapter.search() | catalog size={} | applying: text='{}' categories={} price=[{},{}] offset={} limit={}",
            CATALOG.size(),
            q.isEmpty() ? "(any)" : q,
            categoryFilter.isEmpty() ? "(any)" : categoryFilter,
            priceMin, priceMax == Integer.MAX_VALUE ? "∞" : priceMax,
            offset, limit);

        List<Product> allFiltered = CATALOG.stream()
            .filter(p -> q.isEmpty()
                || p.name().toLowerCase().contains(q)
                || p.description().toLowerCase().contains(q))
            .filter(p -> categoryFilter.isEmpty()
                || p.categories().stream().anyMatch(c -> categoryFilter.contains(c.toLowerCase())))
            .filter(p -> p.priceMinorUnits() >= priceMin && p.priceMinorUnits() <= priceMax)
            .toList();

        List<Product> page = allFiltered.stream().skip(offset).limit(limit).toList();
        boolean hasNext = (offset + limit) < allFiltered.size();
        String nextCursor = hasNext ? String.valueOf(offset + limit) : null;

        log.info("[STEP 7] Filter result: {}/{} products matched | page={} | hasNextPage={}",
            allFiltered.size(), CATALOG.size(), page.stream().map(Product::name).toList(), hasNext);

        return new SearchResult(page, allFiltered.size(), hasNext, nextCursor);
    }

    @Override
    public Optional<Product> getProduct(String id) {
        log.debug("[CATALOG] getProduct('{}')", id);
        return CATALOG.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
