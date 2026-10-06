package com.accenture.UCPAdaptor.service.sap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SapCartService {

    private final SapCommerceClient client;
    private final SapProductService productService;
    private final SapCommerceConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public SapCartService(SapCommerceClient client,
                          SapProductService productService,
                          SapCommerceConfig config) {
        this.client = client;
        this.productService = productService;
        this.config = config;
    }

    public String createCart(List<Map<String, Object>> lineItems) {
        try {
            String json = client.get().post()
                    .uri("/users/anonymous/carts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .body(String.class);

            JsonNode cartNode = mapper.readTree(json);
            String guid = cartNode.path("guid").asText("");

            if (lineItems != null) {
                for (Map<String, Object> li : lineItems) {
                    String productId = String.valueOf(li.getOrDefault("product_id", ""));
                    int qty = ((Number) li.getOrDefault("quantity", 1)).intValue();
                    if (!productId.isBlank() && qty > 0) {
                        addEntry(guid, productId, qty);
                    }
                }
            }

            return buildCartResponse(guid);
        } catch (RestClientException e) {
            return errorJson("Failed to create cart: " + e.getMessage());
        } catch (Exception e) {
            return errorJson("Cart creation error: " + e.getMessage());
        }
    }

    public String getCart(String cartGuid) {
        try {
            return buildCartResponse(cartGuid);
        } catch (Exception e) {
            return errorJson("Failed to get cart: " + e.getMessage());
        }
    }

    public String updateCart(String cartGuid, List<Map<String, Object>> lineItems) {
        try {
            JsonNode currentCart = fetchCartNode(cartGuid);
            JsonNode entries = currentCart.path("entries");

            // Build map productCode -> entryNumber
            java.util.Map<String, Integer> entryMap = new java.util.HashMap<>();
            for (JsonNode entry : entries) {
                String code = entry.path("product").path("code").asText("");
                int entryNum = entry.path("entryNumber").asInt(-1);
                if (!code.isBlank() && entryNum >= 0) {
                    entryMap.put(code, entryNum);
                }
            }

            if (lineItems != null) {
                for (Map<String, Object> li : lineItems) {
                    String productId = String.valueOf(li.getOrDefault("product_id", ""));
                    int qty = ((Number) li.getOrDefault("quantity", 0)).intValue();
                    if (productId.isBlank()) continue;

                    Integer entryNum = entryMap.get(productId);
                    if (qty == 0 && entryNum != null) {
                        deleteEntry(cartGuid, entryNum);
                    } else if (qty > 0 && entryNum != null) {
                        patchEntry(cartGuid, entryNum, qty);
                    } else if (qty > 0) {
                        addEntry(cartGuid, productId, qty);
                    }
                }
            }

            return buildCartResponse(cartGuid);
        } catch (RestClientException e) {
            return errorJson("Failed to update cart: " + e.getMessage());
        } catch (Exception e) {
            return errorJson("Cart update error: " + e.getMessage());
        }
    }

    public String cancelCart(String cartGuid) {
        try {
            client.get().delete()
                    .uri("/users/anonymous/carts/{guid}", cartGuid)
                    .retrieve()
                    .toBodilessEntity();

            ObjectNode result = mapper.createObjectNode();
            ObjectNode cart = mapper.createObjectNode();
            cart.put("id", cartGuid);
            cart.put("status", "canceled");
            cart.set("line_items", mapper.createArrayNode());
            cart.put("currency", "USD");
            cart.set("totals", mapper.createArrayNode());
            result.set("cart", cart);
            return mapper.writeValueAsString(result);
        } catch (RestClientException e) {
            return errorJson("Failed to cancel cart: " + e.getMessage());
        } catch (Exception e) {
            return errorJson("Cart cancel error: " + e.getMessage());
        }
    }

    public String createCheckout(String cartGuid) {
        String continueUrl = config.getStorefrontUrl() + "/en/cart";
        try {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode checkout = mapper.createObjectNode();
            checkout.put("id", cartGuid);
            checkout.put("status", "requires_escalation");
            checkout.put("continue_url", continueUrl);
            result.set("checkout", checkout);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return errorJson("Checkout error: " + e.getMessage());
        }
    }

    // ── OCC helpers ────────────────────────────────────────────────────────────

    private void addEntry(String guid, String productCode, int quantity) {
        String body = "{\"product\":{\"code\":\"" + productCode + "\"},\"quantity\":" + quantity + "}";
        client.get().post()
                .uri("/users/anonymous/carts/{guid}/entries", guid)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private void patchEntry(String guid, int entryNumber, int quantity) {
        String body = "{\"quantity\":" + quantity + "}";
        client.get().patch()
                .uri("/users/anonymous/carts/{guid}/entries/{num}", guid, entryNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private void deleteEntry(String guid, int entryNumber) {
        client.get().delete()
                .uri("/users/anonymous/carts/{guid}/entries/{num}", guid, entryNumber)
                .retrieve()
                .toBodilessEntity();
    }

    private JsonNode fetchCartNode(String guid) throws Exception {
        String json = client.get().get()
                .uri("/users/anonymous/carts/{guid}?fields=FULL", guid)
                .retrieve()
                .body(String.class);
        return mapper.readTree(json);
    }

    private String buildCartResponse(String guid) throws Exception {
        JsonNode occCart = fetchCartNode(guid);
        return buildCartResponseFromNode(guid, occCart);
    }

    private String buildCartResponseFromNode(String guid, JsonNode occCart) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode cart = mapper.createObjectNode();
        cart.put("id", guid);
        cart.put("status", "active");

        String currency = occCart.path("totalPrice").path("currencyIso").asText("USD");
        cart.put("currency", currency);

        ArrayNode lineItems = mapper.createArrayNode();
        for (JsonNode entry : occCart.path("entries")) {
            ObjectNode item = mapper.createObjectNode();
            JsonNode product = entry.path("product");
            item.put("product_id", product.path("code").asText(""));
            item.put("product_name", product.path("name").asText(""));

            double unitDollars = entry.path("basePrice").path("value").asDouble(0);
            item.put("unit_price", Math.round(unitDollars * 100));
            item.put("currency", currency);
            item.put("quantity", entry.path("quantity").asInt(1));

            // Include image_url if OCC returns images in the cart entry's product node
            String imgUrl = productService.extractImageUrlFromNode(product);
            if (imgUrl != null) item.put("image_url", client.absoluteUrl(imgUrl));

            lineItems.add(item);
        }
        cart.set("line_items", lineItems);

        ArrayNode totals = mapper.createArrayNode();
        long subtotal = Math.round(occCart.path("subTotal").path("value").asDouble(0) * 100);
        long shipping = Math.round(occCart.path("deliveryCost").path("value").asDouble(0) * 100);
        long tax = Math.round(occCart.path("totalTax").path("value").asDouble(0) * 100);
        long total = Math.round(occCart.path("totalPriceWithTax").path("value").asDouble(
                occCart.path("totalPrice").path("value").asDouble(0)) * 100);

        totals.add(mapper.createObjectNode().put("type", "subtotal").put("amount", subtotal));
        if (shipping > 0) {
            totals.add(mapper.createObjectNode()
                    .put("type", "fulfillment")
                    .put("display_text", "Shipping")
                    .put("amount", shipping));
        }
        if (tax > 0) {
            totals.add(mapper.createObjectNode().put("type", "tax").put("amount", tax));
        }
        totals.add(mapper.createObjectNode().put("type", "total").put("amount", total));
        cart.set("totals", totals);

        result.set("cart", cart);
        return mapper.writeValueAsString(result);
    }

    private String errorJson(String msg) {
        try {
            ObjectNode e = mapper.createObjectNode();
            e.put("error", msg);
            return mapper.writeValueAsString(e);
        } catch (Exception ex) {
            return "{\"error\":\"" + msg + "\"}";
        }
    }
}
