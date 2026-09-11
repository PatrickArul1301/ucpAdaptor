package com.accenture.UCPAdaptor;

import com.accenture.UCPAdaptor.catalog.CatalogPort;
import com.accenture.UCPAdaptor.catalog.model.Product;
import com.accenture.UCPAdaptor.catalog.model.SearchFilter;
import com.accenture.UCPAdaptor.catalog.model.SearchResult;
import com.accenture.UCPAdaptor.config.UcpProperties;
import com.accenture.UCPAdaptor.ucp.UcpManifestBuilder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class UcpAdaptorController {

    private static final Logger log = LoggerFactory.getLogger(UcpAdaptorController.class);

    private final UcpManifestBuilder manifestBuilder;
    private final CatalogPort catalogPort;
    private final UcpProperties props;

    public UcpAdaptorController(UcpManifestBuilder manifestBuilder, CatalogPort catalogPort, UcpProperties props) {
        this.manifestBuilder = manifestBuilder;
        this.catalogPort = catalogPort;
        this.props = props;
    }

    @GetMapping(value = "/.well-known/ucp", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getUCPSupport(HttpServletRequest request) throws Exception {
        String caller = request.getHeader("User-Agent");
        log.info("◆ UCP DISCOVERY  GET /.well-known/ucp  from {} (User-Agent: {})",
            request.getRemoteAddr(), caller != null ? caller : "unknown");
        String manifest = manifestBuilder.build(request);
        log.info("◆ UCP DISCOVERY  manifest served — see UcpManifestBuilder logs for capability details");
        return manifest;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        SearchResult result = catalogPort.search(new SearchFilter(null, null, null, null), 100, null);
        StringBuilder cards = new StringBuilder();
        for (Product p : result.products()) {
            String price = String.format("$%.2f", p.priceMinorUnits() / 100.0);
            String stockBadge = p.inStock() ? "In Stock" : "Out of Stock";
            String stockColor = p.inStock() ? "#27ae60" : "#e74c3c";
            String stockBg = p.inStock() ? "#e8f5e9" : "#fdecea";
            StringBuilder tags = new StringBuilder();
            for (String cat : p.categories()) {
                tags.append("<span class=\"tag\">").append(cat).append("</span>");
            }
            cards.append("<div class=\"card\">")
                .append("<h2>").append(escapeHtml(p.name())).append("</h2>")
                .append("<p>").append(escapeHtml(p.description())).append("</p>")
                .append("<div class=\"tags\">").append(tags).append("</div>")
                .append("<div class=\"price\">").append(price).append("</div>")
                .append("<div class=\"badge\"><span style=\"background:").append(stockBg)
                .append(";color:").append(stockColor).append(";\">").append(stockBadge).append("</span></div>")
                .append("</div>");
        }
        return "<!DOCTYPE html>\n"
            + "<html lang=\"en\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>" + escapeHtml(props.getStoreName()) + "</title>\n"
            + "  <style>\n"
            + "    * { box-sizing: border-box; margin: 0; padding: 0; }\n"
            + "    body { font-family: Arial, sans-serif; background: #f4f6f8; color: #2c3e50; }\n"
            + "    header { background: #2c3e50; color: white; padding: 24px 32px; text-align: center; }\n"
            + "    header h1 { font-size: 1.8rem; margin-bottom: 6px; }\n"
            + "    header p { color: #bdc3c7; font-size: 0.95rem; }\n"
            + "    header a { color: #5dade2; text-decoration: none; margin-left: 16px; font-size: 0.9rem; }\n"
            + "    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 24px; padding: 32px; max-width: 1100px; margin: 0 auto; }\n"
            + "    .card { background: white; border-radius: 10px; padding: 24px; box-shadow: 0 2px 10px rgba(0,0,0,0.08); display: flex; flex-direction: column; gap: 10px; }\n"
            + "    .card h2 { font-size: 1rem; color: #2c3e50; }\n"
            + "    .card p { font-size: 0.88rem; color: #7f8c8d; flex: 1; }\n"
            + "    .tags { display: flex; gap: 6px; flex-wrap: wrap; }\n"
            + "    .tag { background: #eaf4fb; color: #2980b9; padding: 3px 10px; border-radius: 12px; font-size: 0.75rem; }\n"
            + "    .price { font-size: 1.1rem; font-weight: bold; color: #27ae60; }\n"
            + "    .badge { text-align: center; margin-top: 8px; }\n"
            + "    .badge span { padding: 4px 12px; border-radius: 20px; font-size: 0.75rem; }\n"
            + "  </style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <header>\n"
            + "    <h1>Welcome to " + escapeHtml(props.getStoreName()) + "</h1>\n"
            + "    <p>Powered by Universal Commerce Protocol (UCP v2026-08-25) &nbsp;\n"
            + "      <a href=\"/demo\">Try AI Shopping Demo</a>\n"
            + "    </p>\n"
            + "  </header>\n"
            + "  <div class=\"grid\">\n"
            + cards
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
