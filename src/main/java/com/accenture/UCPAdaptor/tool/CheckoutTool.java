package com.accenture.UCPAdaptor.tool;

import com.accenture.UCPAdaptor.service.CheckoutService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CheckoutTool {

    private final CheckoutService checkoutService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CheckoutTool(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressParams(
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            @JsonProperty("line1") String line1,
            @JsonProperty("line2") String line2,
            @JsonProperty("city") String city,
            @JsonProperty("state") String state,
            @JsonProperty("postal_code") String postalCode,
            @JsonProperty("country") String country
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateCheckoutParams(
            @JsonProperty("cart_id") String cartId,
            @JsonProperty("shipping_address") AddressParams shippingAddress
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateCheckoutParams(
            @JsonProperty("checkout_id") String checkoutId,
            @JsonProperty("shipping_address") AddressParams shippingAddress
    ) {}

    @Tool(name = "create_checkout",
            description = """
              Create a checkout session for a cart. Calculates shipping ($9.99 flat rate) and tax (8.875%). \
              Requires cart_id and shipping_address with fields: first_name, last_name, line1, city, state, \
              postal_code, country. Returns checkout_id and order totals.
              """)
    public String createCheckout(
            @ToolParam(description = "{ cart_id, shipping_address: { first_name, last_name, line1, line2 (optional), city, state, postal_code, country } }", required = true)
            CreateCheckoutParams params) {
        try {
            CheckoutService.Address address = toAddress(params.shippingAddress());
            CheckoutService.Checkout checkout = checkoutService.createCheckout(params.cartId(), address);
            return buildCheckoutResponse("create_checkout", checkout);
        } catch (IllegalArgumentException e) {
            return errorResponse(e.getMessage().equals("CART_NOT_FOUND")
                    ? "Cart not found: " + params.cartId()
                    : e.getMessage(), e.getMessage());
        } catch (Exception e) {
            return errorResponse("Failed to create checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "get_checkout",
            description = "Get the details of an existing checkout session including totals and shipping address.")
    public String getCheckout(
            @ToolParam(description = "The checkout ID returned by create_checkout", required = true)
            String checkoutId) {
        try {
            CheckoutService.Checkout checkout = checkoutService.getCheckout(checkoutId);
            if (checkout == null) return errorResponse("Checkout not found: " + checkoutId, "CHECKOUT_NOT_FOUND");
            return buildCheckoutResponse("get_checkout", checkout);
        } catch (Exception e) {
            return errorResponse("Failed to get checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "update_checkout",
            description = "Update the shipping address on an existing checkout session.")
    public String updateCheckout(
            @ToolParam(description = "{ checkout_id, shipping_address: { first_name, last_name, line1, line2 (optional), city, state, postal_code, country } }", required = true)
            UpdateCheckoutParams params) {
        try {
            CheckoutService.Address address = toAddress(params.shippingAddress());
            CheckoutService.Checkout checkout = checkoutService.updateCheckout(params.checkoutId(), address);
            if (checkout == null) return errorResponse("Checkout not found: " + params.checkoutId(), "CHECKOUT_NOT_FOUND");
            return buildCheckoutResponse("update_checkout", checkout);
        } catch (Exception e) {
            return errorResponse("Failed to update checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "confirm_checkout",
            description = "Confirm a checkout session, locking in the order. Must be called before initiating payment.")
    public String confirmCheckout(
            @ToolParam(description = "The checkout ID to confirm", required = true)
            String checkoutId) {
        try {
            CheckoutService.Checkout checkout = checkoutService.confirmCheckout(checkoutId);
            if (checkout == null) return errorResponse("Checkout not found: " + checkoutId, "CHECKOUT_NOT_FOUND");
            return buildCheckoutResponse("confirm_checkout", checkout);
        } catch (Exception e) {
            return errorResponse("Failed to confirm checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private CheckoutService.Address toAddress(AddressParams p) {
        if (p == null) return null;
        return new CheckoutService.Address(
                p.firstName(), p.lastName(), p.line1(), p.line2(),
                p.city(), p.state(), p.postalCode(), p.country());
    }

    private String buildCheckoutResponse(String capability, CheckoutService.Checkout checkout) throws Exception {
        ObjectNode response = mapper.createObjectNode();

        ObjectNode ucp = mapper.createObjectNode();
        ucp.put("capability", "dev.ucp.shopping.checkout." + capability);
        ucp.put("version", "2026-08-25");
        response.set("ucp", ucp);

        ObjectNode checkoutNode = mapper.createObjectNode();
        checkoutNode.put("checkout_id", checkout.checkoutId());
        checkoutNode.put("cart_id", checkout.cartId());

        CheckoutService.Address addr = checkout.shippingAddress();
        if (addr != null) {
            ObjectNode addrNode = mapper.createObjectNode();
            addrNode.put("first_name", addr.firstName());
            addrNode.put("last_name", addr.lastName());
            addrNode.put("line1", addr.line1());
            if (addr.line2() != null) addrNode.put("line2", addr.line2());
            addrNode.put("city", addr.city());
            addrNode.put("state", addr.state());
            addrNode.put("postal_code", addr.postalCode());
            addrNode.put("country", addr.country());
            checkoutNode.set("shipping_address", addrNode);
        }

        ObjectNode totals = mapper.createObjectNode();
        totals.set("subtotal", amountNode(checkout.subtotal(), checkout.currency()));
        totals.set("shipping", amountNode(checkout.shippingCost(), checkout.currency()));
        totals.set("tax", amountNode(checkout.tax(), checkout.currency()));
        totals.set("total", amountNode(checkout.total(), checkout.currency()));
        checkoutNode.set("totals", totals);

        checkoutNode.put("status", checkout.status());
        response.set("checkout", checkoutNode);

        return mapper.writeValueAsString(response);
    }

    private ObjectNode amountNode(double amount, String currency) {
        ObjectNode node = mapper.createObjectNode();
        node.put("amount", amount);
        node.put("currency_code", currency);
        return node;
    }

    private String errorResponse(String message, String code) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        node.put("code", code);
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"" + message + "\",\"code\":\"" + code + "\"}";
        }
    }
}
