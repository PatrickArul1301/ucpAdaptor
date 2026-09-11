package com.accenture.UCPAdaptor.ucp;

import com.accenture.UCPAdaptor.config.UcpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UcpManifestBuilder {

    private static final Logger log = LoggerFactory.getLogger(UcpManifestBuilder.class);

    private final UcpProperties props;
    private final List<UcpCapabilityContributor> contributors;
    private final ObjectMapper mapper;

    public UcpManifestBuilder(UcpProperties props, List<UcpCapabilityContributor> contributors, ObjectMapper mapper) {
        this.props = props;
        this.contributors = contributors;
        this.mapper = mapper;
        log.info("[STARTUP] UcpManifestBuilder initialised — {} capability contributor(s) registered:",
            contributors.size());
        contributors.forEach(c ->
            log.info("[STARTUP]   capability: {}  spec: {}", c.getCapabilityId(), c.getSpecUrl()));
    }

    public String build(HttpServletRequest request) throws Exception {
        String endpoint = computeEndpoint(request);

        log.info("◆ MANIFEST BUILD  ucp.version={}  MCP endpoint={}", UcpVersion.CURRENT, endpoint);

        ObjectNode root = mapper.createObjectNode();
        ObjectNode ucp = mapper.createObjectNode();
        ucp.put("version", UcpVersion.CURRENT);
        ucp.set("payment_handlers", mapper.createObjectNode());

        ObjectNode services = mapper.createObjectNode();
        ArrayNode shoppingServices = mapper.createArrayNode();
        ObjectNode service = mapper.createObjectNode();
        service.put("version", UcpVersion.CURRENT);
        service.put("spec", "https://ucp.dev/2026-08-25/specification/overview");
        service.put("transport", "mcp");
        service.put("endpoint", endpoint);
        service.put("schema", "https://ucp.dev/2026-08-25/services/shopping/mcp.openrpc.json");
        shoppingServices.add(service);
        services.set("dev.ucp.shopping", shoppingServices);
        ucp.set("services", services);

        // ── CAPABILITY INTERSECTION ──────────────────────────────────────────────
        // Each UcpCapabilityContributor bean (CatalogSearchTool, CartTool) declares
        // its own capability ID, spec URL and schema URL. The manifest collects them
        // all here. A UCP client compares this list against what IT supports to
        // decide which operations it can invoke on this store.
        log.info("◆ CAPABILITY INTERSECTION  building capabilities section ({} contributors):",
            contributors.size());
        ObjectNode capabilities = mapper.createObjectNode();
        for (UcpCapabilityContributor contributor : contributors) {
            ArrayNode capArray = mapper.createArrayNode();
            ObjectNode cap = mapper.createObjectNode();
            cap.put("version", UcpVersion.CURRENT);
            cap.put("spec", contributor.getSpecUrl());
            cap.put("schema", contributor.getSchemaUrl());
            capArray.add(cap);
            capabilities.set(contributor.getCapabilityId(), capArray);
            log.info("◆ CAPABILITY INTERSECTION    + {}  (provided by {})",
                contributor.getCapabilityId(), contributor.getClass().getSimpleName());
        }
        ucp.set("capabilities", capabilities);
        log.info("◆ CAPABILITY INTERSECTION  done — {} capabilities advertised to caller",
            contributors.size());

        root.set("ucp", ucp);

        ArrayNode keys = mapper.createArrayNode();
        ObjectNode key = mapper.createObjectNode();
        key.put("kid", props.getJwkKid());
        key.put("kty", props.getJwkKty());
        key.put("crv", props.getJwkCrv());
        key.put("x", props.getJwkX());
        key.put("y", props.getJwkY());
        key.put("use", props.getJwkUse());
        key.put("alg", props.getJwkAlg());
        keys.add(key);
        root.set("keys", keys);

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private String computeEndpoint(HttpServletRequest request) {
        if (props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
            return props.getBaseUrl() + "/ucp/mcp";
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean nonStandard = ("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443);
        return scheme + "://" + host + (nonStandard ? ":" + port : "") + "/ucp/mcp";
    }
}
