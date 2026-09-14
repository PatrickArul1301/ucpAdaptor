package com.accenture.UCPAdaptor.tools;

import com.accenture.UCPAdaptor.catalog.model.CartItem;
import com.accenture.UCPAdaptor.catalog.model.CheckoutSession;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the dev.ucp.shopping.checkout capability.
 *
 * State machine: incomplete → ready_for_complete → completed
 *   incomplete        cart copied into session, awaiting buyer info
 *   ready_for_complete  both buyerEmail AND shippingAddress are set
 *   completed         order placed, orderId assigned
 */
@Component
public class CheckoutTool implements UcpCapabilityContributor {

    private static final Logger log = LoggerFactory.getLogger(CheckoutTool.class);

    private final ConcurrentHashMap<String, CheckoutSession> sessions = new ConcurrentHashMap<>();
    private final CartTool cartTool;
    private final ObjectMapper mapper;
    private final DemoEventEmitter demoEventEmitter;

    public CheckoutTool(CartTool cartTool, ObjectMapper mapper, DemoEventEmitter demoEventEmitter) {
        this.cartTool = cartTool;
        this.mapper = mapper;
        this.demoEventEmitter = demoEventEmitter;
    }

    @Override public String getCapabilityId() { return "dev.ucp.shopping.checkout"; }
    @Override public String getSpecUrl()       { return "https://ucp.dev/2026-08-25/specification/shopping/checkout"; }
    @Override public String getSchemaUrl()     { return "https://ucp.dev/2026-08-25/schemas/shopping/checkout.json"; }

    @Tool(name = "checkout_create",
          description = "Creates a new checkout session from an existing cart. Returns a checkout ID to use in subsequent checkout steps.")
    public String checkoutCreate(
            @ToolParam(description = "The cart ID returned by cart_create") String cartId) {
        log.info("[STEP 5] Spring AI → checkout_create | cartId={}", cartId);
        emitToolUse("checkout_create", "{\"cartId\":\"" + cartId + "\"}");
        log.info("[STEP 6] SSE 'tool_use' event → browser renders checkout_create call card");

        List<CartItem> items = cartTool.getCartItems(cartId);
        if (items == null) {
            log.warn("[STEP 7] Cart not found: {}", cartId);
            return error("Cart not found: " + cartId);
        }
        if (items.isEmpty()) {
            log.warn("[STEP 7] Cart is empty, cannot create checkout: {}", cartId);
            return error("Cart is empty — add items before starting checkout");
        }

        String checkoutId = UUID.randomUUID().toString();
        CheckoutSession session = new CheckoutSession(checkoutId, cartId, List.copyOf(items),
            null, null, "incomplete", null);
        sessions.put(checkoutId, session);
        log.info("[STEP 7] Checkout created | id={} cartId={} items={} status=incomplete",
            checkoutId, cartId, items.size());

        String result = buildCheckoutJson(session);
        emitToolResult("checkout_create", result);
        log.info("[STEP 8] SSE 'tool_result' event → browser renders checkout_create result card");
        return result;
    }

    @Tool(name = "checkout_update",
          description = "Sets buyer email and/or shipping address on a checkout session. " +
                        "When both are provided the session transitions to ready_for_complete.")
    public String checkoutUpdate(
            @ToolParam(description = "The checkout ID returned by checkout_create") String checkoutId,
            @ToolParam(description = "Buyer's email address", required = false) String buyerEmail,
            @ToolParam(description = "Full shipping address as a single string", required = false) String shippingAddress) {
        log.info("[STEP 5] Spring AI → checkout_update | checkoutId={} email={} address={}",
            checkoutId, buyerEmail, shippingAddress);
        String argsJson = "{\"checkoutId\":\"" + checkoutId + "\""
            + (buyerEmail != null ? ",\"buyerEmail\":\"" + buyerEmail + "\"" : "")
            + (shippingAddress != null ? ",\"shippingAddress\":\"" + shippingAddress + "\"" : "")
            + "}";
        emitToolUse("checkout_update", argsJson);
        log.info("[STEP 6] SSE 'tool_use' event → browser renders checkout_update call card");

        CheckoutSession session = sessions.get(checkoutId);
        if (session == null) {
            log.warn("[STEP 7] Checkout not found: {}", checkoutId);
            return error("Checkout not found: " + checkoutId);
        }
        if ("completed".equals(session.status())) {
            return error("Checkout " + checkoutId + " is already completed");
        }

        String newEmail   = buyerEmail      != null ? buyerEmail      : session.buyerEmail();
        String newAddress = shippingAddress != null ? shippingAddress : session.shippingAddress();
        String newStatus  = (newEmail != null && !newEmail.isBlank()
                          && newAddress != null && !newAddress.isBlank())
                          ? "ready_for_complete" : "incomplete";

        CheckoutSession updated = new CheckoutSession(session.id(), session.cartId(), session.items(),
            newEmail, newAddress, newStatus, null);
        sessions.put(checkoutId, updated);
        log.info("[STEP 7] Checkout updated | id={} status={}", checkoutId, newStatus);

        String result = buildCheckoutJson(updated);
        emitToolResult("checkout_update", result);
        log.info("[STEP 8] SSE 'tool_result' event → browser renders checkout_update result card");
        return result;
    }

    @Tool(name = "checkout_complete",
          description = "Completes a checkout session and places the order. " +
                        "Requires the session to be in ready_for_complete status (both buyer email and shipping address set). " +
                        "Returns an order confirmation with an order ID.")
    public String checkoutComplete(
            @ToolParam(description = "The checkout ID returned by checkout_create") String checkoutId) {
        log.info("[STEP 5] Spring AI → checkout_complete | checkoutId={}", checkoutId);
        emitToolUse("checkout_complete", "{\"checkoutId\":\"" + checkoutId + "\"}");
        log.info("[STEP 6] SSE 'tool_use' event → browser renders checkout_complete call card");

        CheckoutSession session = sessions.get(checkoutId);
        if (session == null) {
            log.warn("[STEP 7] Checkout not found: {}", checkoutId);
            return error("Checkout not found: " + checkoutId);
        }
        if ("completed".equals(session.status())) {
            return error("Checkout " + checkoutId + " is already completed — orderId: " + session.orderId());
        }
        if (!"ready_for_complete".equals(session.status())) {
            return error("Checkout " + checkoutId + " is not ready — set buyerEmail and shippingAddress first. Current status: " + session.status());
        }

        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CheckoutSession completed = new CheckoutSession(session.id(), session.cartId(), session.items(),
            session.buyerEmail(), session.shippingAddress(), "completed", orderId);
        sessions.put(checkoutId, completed);
        log.info("[STEP 7] Order placed | orderId={} checkoutId={}", orderId, checkoutId);

        String result = buildCheckoutJson(completed);
        emitToolResult("checkout_complete", result);
        log.info("[STEP 8] SSE 'tool_result' event → browser renders checkout_complete result card");
        return result;
    }

    private String buildCheckoutJson(CheckoutSession session) {
        try {
            ObjectNode response = mapper.createObjectNode();
            ObjectNode ucpMeta = mapper.createObjectNode();
            ucpMeta.put("capability", "dev.ucp.shopping.checkout");
            ucpMeta.put("version", UcpVersion.CURRENT);
            response.set("ucp", ucpMeta);

            ObjectNode checkout = mapper.createObjectNode();
            checkout.put("id", session.id());
            checkout.put("cartId", session.cartId());
            checkout.put("status", session.status());
            if (session.buyerEmail() != null) checkout.put("buyerEmail", session.buyerEmail());
            if (session.shippingAddress() != null) checkout.put("shippingAddress", session.shippingAddress());
            if (session.orderId() != null) checkout.put("orderId", session.orderId());

            ArrayNode itemsArray = mapper.createArrayNode();
            int totalAmount = 0;
            String currency = "USD";
            for (CartItem item : session.items()) {
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
            checkout.set("items", itemsArray);

            ObjectNode total = mapper.createObjectNode();
            total.put("amount", totalAmount);
            total.put("currency", currency);
            checkout.set("total", total);

            response.set("checkout", checkout);
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return error("Failed to build checkout JSON: " + e.getMessage());
        }
    }

    private String error(String msg) {
        return "{\"error\": \"" + msg.replace("\"", "'") + "\"}";
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
