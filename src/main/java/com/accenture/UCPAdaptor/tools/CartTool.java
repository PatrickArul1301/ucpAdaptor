package com.accenture.UCPAdaptor.tools;

import com.accenture.UCPAdaptor.catalog.CatalogPort;
import com.accenture.UCPAdaptor.catalog.model.CartItem;
import com.accenture.UCPAdaptor.catalog.model.Product;
import com.accenture.UCPAdaptor.ucp.UcpCapabilityContributor;
import com.accenture.UCPAdaptor.ucp.UcpVersion;
import com.accenture.UCPAdaptor.web.DemoEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CartTool implements UcpCapabilityContributor {

    private static final Logger log = LoggerFactory.getLogger(CartTool.class);

    private final ConcurrentHashMap<String, List<CartItem>> carts = new ConcurrentHashMap<>();
    private final CatalogPort catalogPort;
    private final ObjectMapper mapper;
    private final DemoEventEmitter demoEventEmitter;

    public CartTool(CatalogPort catalogPort, ObjectMapper mapper, DemoEventEmitter demoEventEmitter) {
        this.catalogPort = catalogPort;
        this.mapper = mapper;
        this.demoEventEmitter = demoEventEmitter;
    }

    @Override
    public String getCapabilityId() { return "dev.ucp.shopping.cart"; }

    @Override
    public String getSpecUrl() { return "https://ucp.dev/2026-08-25/specification/shopping/cart"; }

    @Override
    public String getSchemaUrl() { return "https://ucp.dev/2026-08-25/schemas/shopping/cart.json"; }

    @Tool(name = "cart_create", description = "Creates a new shopping cart and returns its ID.")
    public String cartCreate() {
        log.info("[STEP 5] Spring AI → cart_create | creating new empty cart");
        emitToolUse("cart_create", "{}");
        log.info("[STEP 6] SSE 'tool_use' event → browser renders cart_create call card");
        String cartId = UUID.randomUUID().toString();
        carts.put(cartId, new ArrayList<>());
        log.info("[STEP 7] Cart created | id={} | total carts in memory={}", cartId, carts.size());
        String result = buildCartJson(cartId, carts.get(cartId));
        log.info("[STEP 8] UCP cart response built | returning to Spring AI → Gemini");
        emitToolResult("cart_create", result);
        log.info("[STEP 8] SSE 'tool_result' event → browser renders cart_create result card");
        return result;
    }

    @Tool(name = "cart_add_item", description = "Adds a product to the cart. If the product is already in the cart, increases the quantity.")
    public String cartAddItem(
            @ToolParam(description = "The cart ID returned by cart_create") String cartId,
            @ToolParam(description = "The product ID to add") String productId,
            @ToolParam(description = "Quantity to add (must be >= 1)") int quantity) {
        log.info("[STEP 5] Spring AI → cart_add_item | cartId={} productId={} qty={}", cartId, productId, quantity);
        emitToolUse("cart_add_item", "{\"cartId\":\"" + cartId + "\",\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}");
        log.info("[STEP 6] SSE 'tool_use' event → browser renders cart_add_item call card");

        List<CartItem> items = carts.get(cartId);
        if (items == null) {
            log.warn("[STEP 7] Cart not found: {}", cartId);
            return error("Cart not found: " + cartId);
        }
        Optional<Product> productOpt = catalogPort.getProduct(productId);
        if (productOpt.isEmpty()) {
            log.warn("[STEP 7] Product not found: {}", productId);
            return error("Product not found: " + productId);
        }
        Product product = productOpt.get();
        log.info("[STEP 7] Resolved product: '{}' @ {} minor units", product.name(), product.priceMinorUnits());

        synchronized (items) {
            boolean found = false;
            for (int i = 0; i < items.size(); i++) {
                CartItem existing = items.get(i);
                if (existing.productId().equals(productId)) {
                    items.set(i, new CartItem(existing.productId(), existing.productName(),
                        existing.quantity() + quantity, existing.unitPriceMinorUnits(), existing.currency()));
                    found = true;
                    log.info("[STEP 7] Merged quantity — '{}' now qty={}", product.name(), existing.quantity() + quantity);
                    break;
                }
            }
            if (!found) {
                items.add(new CartItem(productId, product.name(), quantity, product.priceMinorUnits(), product.currency()));
                log.info("[STEP 7] Added new line item: '{}' qty={}", product.name(), quantity);
            }
        }

        String result = buildCartJson(cartId, items);
        log.info("[STEP 8] UCP cart response built | {} item(s) in cart | returning to Gemini", items.size());
        emitToolResult("cart_add_item", result);
        log.info("[STEP 8] SSE 'tool_result' event → browser renders cart_add_item result card");
        return result;
    }

    @Tool(name = "cart_get", description = "Retrieves the current contents of a shopping cart.")
    public String cartGet(
            @ToolParam(description = "The cart ID") String cartId) {
        log.info("[STEP 5] Spring AI → cart_get | cartId={}", cartId);
        emitToolUse("cart_get", "{\"cartId\":\"" + cartId + "\"}");
        log.info("[STEP 6] SSE 'tool_use' event → browser renders cart_get call card");

        List<CartItem> items = carts.get(cartId);
        if (items == null) {
            log.warn("[STEP 7] Cart not found: {}", cartId);
            return error("Cart not found: " + cartId);
        }
        log.info("[STEP 7] Cart found | {} item(s)", items.size());
        String result = buildCartJson(cartId, items);
        log.info("[STEP 8] UCP cart response built | returning to Gemini");
        emitToolResult("cart_get", result);
        log.info("[STEP 8] SSE 'tool_result' event → browser renders cart_get result card");
        return result;
    }

    @Tool(name = "cart_remove_item", description = "Removes a product from the cart.")
    public String cartRemoveItem(
            @ToolParam(description = "The cart ID") String cartId,
            @ToolParam(description = "The product ID to remove") String productId) {
        log.info("[STEP 5] Spring AI → cart_remove_item | cartId={} productId={}", cartId, productId);
        emitToolUse("cart_remove_item", "{\"cartId\":\"" + cartId + "\",\"productId\":\"" + productId + "\"}");
        log.info("[STEP 6] SSE 'tool_use' event → browser renders cart_remove_item call card");

        List<CartItem> items = carts.get(cartId);
        if (items == null) {
            log.warn("[STEP 7] Cart not found: {}", cartId);
            return error("Cart not found: " + cartId);
        }
        synchronized (items) {
            int before = items.size();
            items.removeIf(item -> item.productId().equals(productId));
            log.info("[STEP 7] Removed product '{}' | items before={} after={}", productId, before, items.size());
        }
        String result = buildCartJson(cartId, items);
        log.info("[STEP 8] UCP cart response built | returning to Gemini");
        emitToolResult("cart_remove_item", result);
        log.info("[STEP 8] SSE 'tool_result' event → browser renders cart_remove_item result card");
        return result;
    }

    /** Returns a snapshot of cart items, or null if the cart does not exist. */
    public List<CartItem> getCartItems(String cartId) {
        List<CartItem> items = carts.get(cartId);
        return items != null ? List.copyOf(items) : null;
    }

    private String buildCartJson(String cartId, List<CartItem> items) {
        try {
            ObjectNode response = mapper.createObjectNode();
            ObjectNode ucpMeta = mapper.createObjectNode();
            ucpMeta.put("capability", "dev.ucp.shopping.cart");
            ucpMeta.put("version", UcpVersion.CURRENT);
            response.set("ucp", ucpMeta);

            ObjectNode cart = mapper.createObjectNode();
            cart.put("id", cartId);
            ArrayNode itemsArray = mapper.createArrayNode();
            int totalAmount = 0;
            String currency = "USD";
            for (CartItem item : items) {
                ObjectNode itemNode = mapper.createObjectNode();
                itemNode.put("productId", item.productId());
                itemNode.put("productName", item.productName());
                itemNode.put("quantity", item.quantity());
                itemNode.put("unitPriceMinorUnits", item.unitPriceMinorUnits());
                itemNode.put("currency", item.currency());
                itemNode.put("subtotalMinorUnits", item.quantity() * item.unitPriceMinorUnits());
                itemsArray.add(itemNode);
                totalAmount += item.quantity() * item.unitPriceMinorUnits();
                currency = item.currency();
            }
            cart.set("items", itemsArray);
            ObjectNode total = mapper.createObjectNode();
            total.put("amount", totalAmount);
            total.put("currency", currency);
            cart.set("total", total);
            response.set("cart", cart);

            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return error("Failed to build cart JSON: " + e.getMessage());
        }
    }

    private String error(String msg) {
        return "{\"error\": \"" + msg + "\"}";
    }

    private void emitToolUse(String name, String argsJson) {
        if (demoEventEmitter.getEmitter() != null) {
            demoEventEmitter.emit("tool_use", "{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}");
        }
    }

    private void emitToolResult(String name, String resultJson) {
        if (demoEventEmitter.getEmitter() != null) {
            demoEventEmitter.emit("tool_result", "{\"name\":\"" + name + "\",\"result\":" + resultJson + "}");
        }
    }
}
