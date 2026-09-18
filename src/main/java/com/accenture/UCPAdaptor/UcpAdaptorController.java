package com.accenture.UCPAdaptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class UcpAdaptorController {

    private final ProductCatalogService catalogService;
    private final String publicUrl;

    public UcpAdaptorController(ProductCatalogService catalogService,
                                @Value("${ucp.public-url:http://localhost:8080}") String publicUrl) {
        this.catalogService = catalogService;
        this.publicUrl = publicUrl.stripTrailing().replaceAll("/+$", "");
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        List<ProductCatalogService.Product> products = catalogService.getAll().stream().limit(20).toList();

        StringBuilder cards = new StringBuilder();
        for (ProductCatalogService.Product p : products) {
            String imgTag = p.imageUrl() != null && !p.imageUrl().isBlank()
                    ? "<img src=\"" + escHtml(p.imageUrl()) + "\" alt=\"\" loading=\"lazy\">"
                    : "";
            String priceStr = String.format("$%.2f", p.price());
            StringBuilder tags = new StringBuilder();
            for (String cat : p.categories()) {
                tags.append("<span class=\"tag\">").append(escHtml(cat)).append("</span>");
            }
            String badge = p.inStock() ? "<span class=\"in-stock\">In Stock</span>"
                    : "<span class=\"out-stock\">Out of Stock</span>";
            String link = p.url() != null && !p.url().isBlank()
                    ? "<a href=\"" + escHtml(p.url()) + "\" target=\"_blank\" class=\"buy-link\">View on Samsung</a>"
                    : "";
            cards.append("""
                    <div class="card">
                      %s
                      <h2>%s</h2>
                      <p>%s</p>
                      <div class="tags">%s</div>
                      <div class="price">%s</div>
                      <div class="badge">%s</div>
                      %s
                    </div>
                    """.formatted(imgTag, escHtml(p.name()), escHtml(p.description()),
                    tags, priceStr, badge, link));
        }

        if (products.isEmpty()) {
            cards.append("""
                    <div class="card" style="grid-column:1/-1;text-align:center">
                      <p>No products loaded. Run <code>SamsungCatalogFetcher</code> to generate the catalog.</p>
                    </div>
                    """);
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Samsung Galaxy S Store — UCP Adaptor</title>
                  <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: Arial, sans-serif; background: #f4f6f8; color: #2c3e50; }
                    header { background: #1428a0; color: white; padding: 24px 32px; text-align: center; }
                    header h1 { font-size: 1.8rem; margin-bottom: 6px; }
                    header p { color: #a0b0e0; font-size: 0.95rem; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 24px; padding: 32px; max-width: 1200px; margin: 0 auto; }
                    .card { background: white; border-radius: 10px; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.08); display: flex; flex-direction: column; gap: 10px; }
                    .card img { width: 100%; height: 180px; object-fit: contain; border-radius: 6px; background: #f8f8f8; }
                    .card h2 { font-size: 0.95rem; color: #2c3e50; line-height: 1.3; }
                    .card p { font-size: 0.82rem; color: #7f8c8d; flex: 1; }
                    .tags { display: flex; gap: 6px; flex-wrap: wrap; }
                    .tag { background: #e8f0fe; color: #1428a0; padding: 3px 10px; border-radius: 12px; font-size: 0.72rem; }
                    .price { font-size: 1.15rem; font-weight: bold; color: #27ae60; }
                    .badge { text-align: center; }
                    .in-stock { background: #e8f5e9; color: #27ae60; padding: 4px 12px; border-radius: 20px; font-size: 0.75rem; }
                    .out-stock { background: #fce4e4; color: #c0392b; padding: 4px 12px; border-radius: 20px; font-size: 0.75rem; }
                    .buy-link { display: block; text-align: center; background: #1428a0; color: white; padding: 8px 16px; border-radius: 6px; text-decoration: none; font-size: 0.82rem; margin-top: 4px; }
                    .buy-link:hover { background: #0d1e7e; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>Samsung Galaxy S Smartphones</h1>
                    <p>Powered by Universal Commerce Protocol (UCP v2026-08-25)</p>
                  </header>
                  <div class="grid">
                """ + cards + """
                  </div>
                </body>
                </html>
                """;
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @GetMapping(value = "/.well-known/ucp", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getUCPSupport() {
        String mcpEndpoint = publicUrl + "/ucp/mcp";
        return """
                {
                  "ucp": {
                    "version": "2026-08-25",
                    "payment_handlers": {},
                    "services": {
                      "dev.ucp.shopping": [
                        {
                          "version": "2026-08-25",
                          "spec": "https://ucp.dev/2026-08-25/specification/overview",
                          "transport": "mcp",
                          "endpoint": "%s",
                          "schema": "https://ucp.dev/2026-08-25/services/shopping/mcp.openrpc.json"
                        }
                      ]
                    },""".formatted(mcpEndpoint) + """
                    "capabilities": {

                      "dev.ucp.shopping.catalog.search": [
                        {
                          "version": "2026-08-25",
                          "spec": "https://ucp.dev/2026-08-25/specification/shopping/catalog/search",
                          "schema": "https://ucp.dev/2026-08-25/schemas/shopping/catalog_search.json"

                        }
                      ]
                    }
                  },
                  "keys": [
                    {
                      "kid": "ucpadaptor-key-2026",
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "08dxr3fmRRgHgI5AuNfAzgdLLKxs5_V6LssqemQkSHA",
                      "y": "Jg4OMbHECBZF0EaL6lWO8Q4zudRj2lYm6JShabSDaq8",
                      "use": "sig",
                      "alg": "ES256"
                    }
                  ]
                }
                """;
    }

}
