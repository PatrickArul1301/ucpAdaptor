package com.accenture.UCPAdaptor.controller;

import com.accenture.UCPAdaptor.service.sap.SapCartService;
import com.accenture.UCPAdaptor.service.sap.SapProductService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 2.0 MCP endpoint for SAP Commerce electronics store.
 * Bridges UCP tool calls to OCC v2 APIs without conflicting with the
 * Spring AI-managed Samsung tools at /ucp/mcp.
 */
@RestController
@RequestMapping("/ucp/sap-mcp")
public class SapMcpController {

    private final SapProductService productService;
    private final SapCartService cartService;
    private final ObjectMapper mapper = new ObjectMapper();

    public SapMcpController(SapProductService productService, SapCartService cartService) {
        this.productService = productService;
        this.cartService = cartService;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public String handle(@RequestBody String requestBody) {
        try {
            JsonNode req = mapper.readTree(requestBody);
            String method = req.path("method").asText("");
            JsonNode id = req.has("id") ? req.get("id") : mapper.nullNode();

            return switch (method) {
                case "initialize"  -> okResult(id, buildServerInfo());
                case "tools/list"  -> okResult(id, buildToolList());
                case "tools/call"  -> dispatchToolCall(id, req.path("params"));
                default            -> okResult(id, mapper.createObjectNode());
            };
        } catch (Exception e) {
            return errorResponse(null, -32700, "Parse error: " + e.getMessage());
        }
    }

    // ── Dispatch ───────────────────────────────────────────────────────────────

    private String dispatchToolCall(JsonNode id, JsonNode params) throws Exception {
        String toolName = params.path("name").asText("");
        JsonNode args = params.path("arguments");

        String resultText = switch (toolName) {
            case "search_catalog"     -> productService.searchProducts(args.path("query").asText(""));
            case "get_product_details"-> productService.getProduct(args.path("product_code").asText(""));
            case "create_cart"        -> cartService.createCart(extractLineItems(args));
            case "get_cart"           -> cartService.getCart(args.path("id").asText(""));
            case "update_cart"        -> cartService.updateCart(args.path("id").asText(""), extractLineItems(args));
            case "cancel_cart"        -> cartService.cancelCart(args.path("id").asText(""));
            case "create_checkout"    -> {
                String cartId = args.path("cart_id").asText(args.path("id").asText(""));
                yield cartService.createCheckout(cartId);
            }
            default -> "{\"error\":\"Unknown tool: " + toolName + "\"}";
        };

        ObjectNode content = mapper.createObjectNode();
        ArrayNode contentArr = mapper.createArrayNode();
        ObjectNode textNode = mapper.createObjectNode();
        textNode.put("type", "text");
        textNode.put("text", resultText);
        contentArr.add(textNode);
        content.set("content", contentArr);
        content.put("isError", false);
        return okResult(id, content);
    }

    // ── Tool list ──────────────────────────────────────────────────────────────

    private ObjectNode buildToolList() {
        ArrayNode tools = mapper.createArrayNode();

        tools.add(tool("search_catalog",
                "Search the SAP Commerce electronics product catalog",
                schema("query", "string", "Search query (e.g. camera, headphones)")));

        tools.add(tool("get_product_details",
                "Get detailed information for a specific product by code",
                schema("product_code", "string", "Product code from search results")));

        tools.add(tool("create_cart",
                "Create a new shopping cart, optionally with initial items. " +
                "Pass line_items as [{product_id, quantity}].",
                optionalLineItemsSchema()));

        tools.add(tool("get_cart",
                "Get current cart contents including items, quantities, and price totals",
                schema("id", "string", "Cart GUID returned by create_cart")));

        tools.add(tool("update_cart",
                "Update cart items. quantity=0 removes the item.",
                updateCartSchema()));

        tools.add(tool("cancel_cart",
                "Cancel a shopping cart",
                schema("id", "string", "Cart GUID to cancel")));

        tools.add(tool("create_checkout",
                "Initiate checkout for a cart. Returns a continue_url directing the user to " +
                "complete purchase on the SAP Commerce storefront.",
                schema("cart_id", "string", "Cart GUID to check out")));

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    // ── Server info ────────────────────────────────────────────────────────────

    private ObjectNode buildServerInfo() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode caps = mapper.createObjectNode();
        caps.set("tools", mapper.createObjectNode());
        result.set("capabilities", caps);
        ObjectNode info = mapper.createObjectNode();
        info.put("name", "ucp-sap-commerce-adaptor");
        info.put("version", "1.0.0");
        result.set("serverInfo", info);
        return result;
    }

    // ── JSON-RPC helpers ───────────────────────────────────────────────────────

    private String okResult(JsonNode id, ObjectNode result) throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.set("result", result);
        return mapper.writeValueAsString(resp);
    }

    private String errorResponse(JsonNode id, int code, String message) {
        try {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.set("id", id);
            ObjectNode err = mapper.createObjectNode();
            err.put("code", code);
            err.put("message", message);
            resp.set("error", err);
            return mapper.writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Internal error\"}}";
        }
    }

    // ── Schema builders ────────────────────────────────────────────────────────

    private ObjectNode schema(String paramName, String type, String description) {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode param = mapper.createObjectNode();
        param.put("type", type);
        param.put("description", description);
        props.set(paramName, param);
        s.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add(paramName);
        s.set("required", required);
        return s;
    }

    private ObjectNode optionalLineItemsSchema() {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode lineItems = mapper.createObjectNode();
        lineItems.put("type", "array");
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "object");
        ObjectNode itemProps = mapper.createObjectNode();
        ObjectNode pid = mapper.createObjectNode(); pid.put("type", "string");
        ObjectNode qty = mapper.createObjectNode(); qty.put("type", "integer");
        itemProps.set("product_id", pid);
        itemProps.set("quantity", qty);
        items.set("properties", itemProps);
        lineItems.set("items", items);
        props.set("line_items", lineItems);
        s.set("properties", props);
        return s;
    }

    private ObjectNode updateCartSchema() {
        ObjectNode s = optionalLineItemsSchema();
        ObjectNode props = (ObjectNode) s.get("properties");
        ObjectNode id = mapper.createObjectNode();
        id.put("type", "string");
        id.put("description", "Cart GUID");
        props.set("id", id);
        ArrayNode required = mapper.createArrayNode();
        required.add("id");
        required.add("line_items");
        s.set("required", required);
        return s;
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        t.set("inputSchema", inputSchema);
        return t;
    }

    // ── Arg extraction ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractLineItems(JsonNode args) {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode items = args.path("line_items");
        if (!items.isArray()) return result;
        for (JsonNode item : items) {
            Map<String, Object> m = new HashMap<>();
            if (item.has("product_id")) m.put("product_id", item.path("product_id").asText(""));
            if (item.has("quantity"))   m.put("quantity", item.path("quantity").asInt(0));
            result.add(m);
        }
        return result;
    }
}
