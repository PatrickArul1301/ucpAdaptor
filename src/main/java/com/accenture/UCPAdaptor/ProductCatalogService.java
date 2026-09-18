package com.accenture.UCPAdaptor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Product(
            String id,
            String name,
            String description,
            List<String> categories,
            double price,
            String currency,
            boolean inStock,
            String url,
            String imageUrl
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogEntry(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("categories") List<String> categories,
            @JsonProperty("price") double price,
            @JsonProperty("currency") String currency,
            @JsonProperty("in_stock") boolean inStock,
            @JsonProperty("url") String url,
            @JsonProperty("image_url") String imageUrl
    ) {}

    private List<Product> products = List.of();

    @PostConstruct
    void load() {
        ClassPathResource resource = new ClassPathResource("data/samsung-catalog.json");
        if (!resource.exists()) {
            log.warn("data/samsung-catalog.json not found — catalog is empty. Run SamsungCatalogFetcher to generate it.");
            return;
        }
        try {
            List<CatalogEntry> entries = MAPPER.readValue(
                    resource.getInputStream(), new TypeReference<>() {});
            products = entries.stream()
                    .map(e -> new Product(e.id(), e.name(), e.description(), e.categories(),
                            e.price(), e.currency(), e.inStock(), e.url(), e.imageUrl()))
                    .toList();
            log.info("Loaded {} Samsung Galaxy S products from catalog", products.size());
        } catch (Exception ex) {
            log.warn("Failed to load samsung-catalog.json: {}", ex.getMessage());
        }
    }

    public List<Product> getAll() {
        return products;
    }
}
