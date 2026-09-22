package com.accenture.UCPAdaptor.tool;

import com.accenture.UCPAdaptor.service.CheckoutService;
import com.accenture.UCPAdaptor.service.OrderService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CheckoutTool {

    private final CheckoutService checkoutService;
    private final OrderService orderService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CheckoutTool(CheckoutService checkoutService, OrderService orderService) {
        this.checkoutService = checkoutService;
        this.orderService = orderService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuyerParams(
            @JsonProperty("email") String email,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName
    ) {}

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
    public record CheckoutInput(
            @JsonProperty("cart_id") String cartId,
            @JsonProperty("buyer") BuyerParams buyer,
            @JsonProperty("shipping_address") AddressParams shippingAddress
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateCheckoutParams(
            @JsonProperty("checkout") CheckoutInput checkout
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateCheckoutInput(
            @JsonProperty("buyer") BuyerParams buyer,
            @JsonProperty("shipping_address") AddressParams shippingAddress
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateCheckoutParams(
            @JsonProperty("id") String id,
            @JsonProperty("checkout") UpdateCheckoutInput checkout
    ) {}

    @Tool(name = "create_checkout",
            description = """
              Create a checkout session from a cart. Provide checkout.cart_id (required). \
              Optionally include checkout.buyer (email, first_name, last_name) and \
              checkout.shipping_address (line1, city, state, postal_code, country). \
              When both buyer email and shipping address are provided, status will be ready_for_complete. \
              Returns checkout ID and order totals in minor units (cents).
              """)
    public String createCheckout(
            @ToolParam(description = """
                    { checkout: { cart_id (required), \
                    buyer: { email, first_name, last_name }, \
                    shipping_address: { line1, line2 (optional), city, state, postal_code, country } } }
                    """, required = true)
            CreateCheckoutParams params) {
        try {
            CheckoutInput ci = params.checkout();
            if (ci == null) return errorResponse("checkout object is required", "INVALID_PARAMS");
            CheckoutService.Buyer buyer = toBuyer(ci.buyer());
            CheckoutService.Address address = toAddress(ci.shippingAddress());
            CheckoutService.Checkout checkout = checkoutService.createCheckout(ci.cartId(), buyer, address);
            return buildCheckoutResponse(checkout, null);
        } catch (IllegalArgumentException e) {
            return errorResponse(e.getMessage().equals("CART_NOT_FOUND")
                    ? "Cart not found" : e.getMessage(), e.getMessage());
        } catch (Exception e) {
            return errorResponse("Failed to create checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "get_checkout",
            description = "Get the details of an existing checkout session including buyer, shipping address, and totals in minor units (cents).")
    public String getCheckout(
            @ToolParam(description = "The checkout ID returned by create_checkout", required = true)
            String id) {
        try {
            CheckoutService.Checkout checkout = checkoutService.getCheckout(id);
            if (checkout == null) return errorResponse("Checkout not found: " + id, "CHECKOUT_NOT_FOUND");
            return buildCheckoutResponse(checkout, null);
        } catch (Exception e) {
            return errorResponse("Failed to get checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "update_checkout",
            description = """
              Update buyer info and/or shipping address on a checkout. \
              Provide id (checkout ID) and checkout object with buyer and/or shipping_address. \
              When both buyer.email and a complete shipping_address are present, \
              status becomes ready_for_complete and payment can be requested.
              """)
    public String updateCheckout(
            @ToolParam(description = """
                    { id (required): checkout ID, \
                    checkout: { buyer: { email, first_name, last_name }, \
                    shipping_address: { line1, city, state, postal_code, country } } }
                    """, required = true)
            UpdateCheckoutParams params) {
        try {
            UpdateCheckoutInput ci = params.checkout();
            CheckoutService.Buyer buyer = ci != null ? toBuyer(ci.buyer()) : null;
            CheckoutService.Address address = ci != null ? toAddress(ci.shippingAddress()) : null;
            CheckoutService.Checkout checkout = checkoutService.updateCheckout(params.id(), buyer, address);
            if (checkout == null) return errorResponse("Checkout not found: " + params.id(), "CHECKOUT_NOT_FOUND");
            return buildCheckoutResponse(checkout, null);
        } catch (Exception e) {
            return errorResponse("Failed to update checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "complete_checkout",
            description = """
              Finalize a checkout and place the order. The checkout must be in ready_for_complete status \
              (buyer email and shipping address both set). Returns status=completed with the order object \
              containing the order ID. All amounts are in minor units (cents).
              """)
    public String completeCheckout(
            @ToolParam(description = "The checkout ID to complete", required = true)
            String id) {
        try {
            CheckoutService.Checkout checkout = checkoutService.completeCheckout(id);
            if (checkout == null) return errorResponse("Checkout not found: " + id, "CHECKOUT_NOT_FOUND");

            OrderService.Order order = checkout.orderId() != null
                    ? orderService.getOrder(checkout.orderId()) : null;
            return buildCheckoutResponse(checkout, order);
        } catch (Exception e) {
            return errorResponse("Failed to complete checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "cancel_checkout",
            description = "Cancel a checkout session. A canceled checkout cannot be completed.")
    public String cancelCheckout(
            @ToolParam(description = "The checkout ID to cancel", required = true)
            String id) {
        try {
            CheckoutService.Checkout checkout = checkoutService.cancelCheckout(id);
            if (checkout == null) return errorResponse("Checkout not found: " + id, "CHECKOUT_NOT_FOUND");
            return buildCheckoutResponse(checkout, null);
        } catch (Exception e) {
            return errorResponse("Failed to cancel checkout: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private CheckoutService.Buyer toBuyer(BuyerParams p) {
        if (p == null) return null;
        return new CheckoutService.Buyer(p.email(), p.firstName(), p.lastName());
    }

    private CheckoutService.Address toAddress(AddressParams p) {
        if (p == null) return null;
        return new CheckoutService.Address(
                p.firstName(), p.lastName(), p.line1(), p.line2(),
                p.city(), p.state(), p.postalCode(), p.country());
    }

    private String buildCheckoutResponse(CheckoutService.Checkout checkout, OrderService.Order order) throws Exception {
        ObjectNode response = mapper.createObjectNode();

        ObjectNode ucp = mapper.createObjectNode();
        ucp.put("capability", "dev.ucp.shopping.checkout");
        ucp.put("version", "2026-08-25");
        response.set("ucp", ucp);

        ObjectNode checkoutNode = mapper.createObjectNode();
        checkoutNode.put("id", checkout.id());
        checkoutNode.put("cart_id", checkout.cartId());

        CheckoutService.Buyer buyer = checkout.buyer();
        if (buyer != null) {
            ObjectNode buyerNode = mapper.createObjectNode();
            if (buyer.email() != null) buyerNode.put("email", buyer.email());
            if (buyer.firstName() != null) buyerNode.put("first_name", buyer.firstName());
            if (buyer.lastName() != null) buyerNode.put("last_name", buyer.lastName());
            checkoutNode.set("buyer", buyerNode);
        }

        CheckoutService.Address addr = checkout.shippingAddress();
        if (addr != null) {
            ObjectNode addrNode = mapper.createObjectNode();
            if (addr.firstName() != null) addrNode.put("first_name", addr.firstName());
            if (addr.lastName() != null) addrNode.put("last_name", addr.lastName());
            if (addr.line1() != null) addrNode.put("line1", addr.line1());
            if (addr.line2() != null) addrNode.put("line2", addr.line2());
            if (addr.city() != null) addrNode.put("city", addr.city());
            if (addr.state() != null) addrNode.put("state", addr.state());
            if (addr.postalCode() != null) addrNode.put("postal_code", addr.postalCode());
            if (addr.country() != null) addrNode.put("country", addr.country());
            checkoutNode.set("shipping_address", addrNode);
        }

        checkoutNode.put("currency", checkout.currency());
        ArrayNode totals = mapper.createArrayNode();
        totals.add(mapper.createObjectNode().put("type", "subtotal").put("amount", checkout.subtotal()));
        totals.add(mapper.createObjectNode().put("type", "fulfillment").put("display_text", "Shipping").put("amount", checkout.shippingCost()));
        totals.add(mapper.createObjectNode().put("type", "tax").put("amount", checkout.tax()));
        totals.add(mapper.createObjectNode().put("type", "total").put("amount", checkout.total()));
        checkoutNode.set("totals", totals);

        checkoutNode.put("status", checkout.status());
        if (checkout.orderId() != null) {
            checkoutNode.put("order_id", checkout.orderId());
        }
        response.set("checkout", checkoutNode);

        if (order != null) {
            response.set("order", buildOrderNode(order));
        }

        return mapper.writeValueAsString(response);
    }

    private ObjectNode buildOrderNode(OrderService.Order order) {
        ObjectNode orderNode = mapper.createObjectNode();
        orderNode.put("id", order.id());
        orderNode.put("checkout_id", order.checkoutId());

        ArrayNode items = mapper.createArrayNode();
        for (var item : order.lineItems()) {
            ObjectNode itemNode = mapper.createObjectNode();
            itemNode.put("product_id", item.productId());
            itemNode.put("product_name", item.productName());
            itemNode.put("unit_price", item.unitPrice());
            itemNode.put("quantity", item.quantity());
            if (item.imageUrl() != null) itemNode.put("image_url", item.imageUrl());
            items.add(itemNode);
        }
        orderNode.set("line_items", items);

        ArrayNode totals = mapper.createArrayNode();
        totals.add(mapper.createObjectNode().put("type", "subtotal").put("amount", order.subtotal()));
        totals.add(mapper.createObjectNode().put("type", "fulfillment").put("display_text", "Shipping").put("amount", order.shippingCost()));
        totals.add(mapper.createObjectNode().put("type", "tax").put("amount", order.tax()));
        totals.add(mapper.createObjectNode().put("type", "total").put("amount", order.total()));
        orderNode.set("totals", totals);
        orderNode.put("currency", order.currency());

        return orderNode;
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
