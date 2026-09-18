package com.accenture.UCPAdaptor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-time utility: fetches all Samsung products, filters to Galaxy S phones,
 * and writes a compact catalog to src/main/resources/data/samsung-catalog.json.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.accenture.UCPAdaptor.SamsungCatalogFetcher
 */
public class SamsungCatalogFetcher {

    private static final String API_BASE =
            "https://p1-smu-api-cdn.shop.samsung.com/tokocommercewebservices/v2/us/products/all";
    private static final int PAGE_SIZE = 50;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Samsung API response records ──────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SamsungPage(
            @JsonProperty("products") List<SamsungRawProduct> products,
            @JsonProperty("pagination") SamsungPagination pagination
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SamsungRawProduct(
            @JsonProperty("code") String code,
            @JsonProperty("name") String name,
            @JsonProperty("baseProductName") String baseProductName,
            @JsonProperty("description") String description,
            @JsonProperty("externalUrl") String externalUrl,
            @JsonProperty("categories") List<SamsungCategory> categories,
            @JsonProperty("price") SamsungPrice price,
            @JsonProperty("color") SamsungColor color,
            @JsonProperty("stock") SamsungStock stock,
            @JsonProperty("picture") SamsungPicture picture
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SamsungCategory(
            @JsonProperty("categoryType") String categoryType,
            @JsonProperty("name") String name,
            @JsonProperty("hierarchy") String hierarchy
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SamsungPrice(
            @JsonProperty("value") double value,
            @JsonProperty("currencyIso") String currencyIso
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SamsungColor(
            @JsonProperty("colorName") String colorName
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SamsungStock(
            @JsonProperty("stockLevelStatus") String stockLevelStatus
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SamsungPicture(
            @JsonProperty("url") String url
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SamsungPagination(
            @JsonProperty("totalPages") int totalPages,
            @JsonProperty("currentPage") int currentPage,
            @JsonProperty("totalResults") int totalResults
    ) {}

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        List<Map<String, Object>> catalog = new ArrayList<>();

        System.out.println("Fetching Samsung product catalog...");
        SamsungPage firstPage = fetchPage(client, 1);
        int totalPages = firstPage.pagination().totalPages();
        System.out.printf("Total pages: %d, total products: %d%n",
                totalPages, firstPage.pagination().totalResults());

        processPage(firstPage, catalog);

        for (int page = 2; page <= totalPages; page++) {
            SamsungPage p = fetchPage(client, page);
            processPage(p, catalog);
            if (page % 5 == 0) {
                System.out.printf("  Page %d/%d done, Galaxy S found so far: %d%n",
                        page, totalPages, catalog.size());
            }
        }

        System.out.printf("Galaxy S products found: %d%n", catalog.size());

        Path outPath = Paths.get("src/main/resources/data/samsung-catalog.json");
        Files.createDirectories(outPath.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outPath.toFile(), catalog);
        System.out.println("Written to " + outPath.toAbsolutePath());
    }

    private static SamsungPage fetchPage(HttpClient client, int page) throws Exception {
        String url = API_BASE + "?currentPage=" + page + "&fields=BULK_SIMPLE_INFO&pageSize=" + PAGE_SIZE;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://www.samsung.com/")
                .header("Origin", "https://www.samsung.com")
                .header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "cross-site")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().isBlank()) {
            throw new RuntimeException("API error on page " + page + ": HTTP " + response.statusCode());
        }
        return MAPPER.readValue(response.body(), SamsungPage.class);
    }

    private static void processPage(SamsungPage page, List<Map<String, Object>> catalog) {
        if (page.products() == null) return;
        for (SamsungRawProduct p : page.products()) {
            if (!isGalaxyS(p)) continue;

            List<String> navCatNames = p.categories() == null ? List.of() :
                    p.categories().stream()
                            .filter(c -> "NAV".equals(c.categoryType()) && c.hierarchy() != null && c.hierarchy().contains("/Galaxy S/"))
                            .map(SamsungCategory::name)
                            .toList();

            // Add MERCH "Mobile Phone" category for search compatibility
            List<String> allCats = new ArrayList<>(navCatNames);
            if (p.categories() != null) {
                p.categories().stream()
                        .filter(c -> "MERCH".equals(c.categoryType()) && c.name() != null)
                        .map(SamsungCategory::name)
                        .filter(n -> !allCats.contains(n))
                        .forEach(allCats::add);
            }

            String colorName = (p.color() != null && p.color().colorName() != null) ? p.color().colorName() : "";
            String description = p.name() + (colorName.isBlank() ? "" : ", " + colorName);

            double price = p.price() != null ? p.price().value() : 0.0;
            String currency = (p.price() != null && p.price().currencyIso() != null) ? p.price().currencyIso() : "USD";
            boolean inStock = p.stock() != null && "inStock".equals(p.stock().stockLevelStatus());
            String productUrl = p.externalUrl() != null ? "https://www.samsung.com" + p.externalUrl() : "";
            String imageUrl = (p.picture() != null && p.picture().url() != null) ? p.picture().url() : "";

            catalog.add(Map.of(
                    "id", p.code() != null ? p.code() : "",
                    "name", p.name() != null ? p.name() : "",
                    "description", description,
                    "categories", allCats,
                    "price", price,
                    "currency", currency,
                    "in_stock", inStock,
                    "url", productUrl,
                    "image_url", imageUrl
            ));
        }
    }

    private static boolean isGalaxyS(SamsungRawProduct p) {
        if (p.categories() == null) return false;
        return p.categories().stream().anyMatch(c ->
                "NAV".equals(c.categoryType()) && c.hierarchy() != null && c.hierarchy().contains("/Galaxy S/"));
    }
}
