package com.accenture.UCPAdaptor.tool;

import com.accenture.UCPAdaptor.service.CartService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CartTool {

    private final CartService cartService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CartTool(CartService cartService) {
        this.cartService = cartService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineItemInput(
            @JsonProperty("product_id") String productId,
            @JsonProperty("quantity") Integer quantity
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateCartParams(
            @JsonProperty("line_items") List<LineItemInput> lineItems
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateCartParams(
            @JsonProperty("id") String id,
            @JsonProperty("line_items") List<LineItemInput> lineItems
    ) {}

    @Tool(name = "create_cart",
            description = """
              Create a new shopping cart, optionally with initial items. \
              Provide line_items as an array of { product_id, quantity } objects using \
              exact product IDs from search_catalog. Returns the cart with all items and subtotal. \
              If line_items is omitted or empty, an empty cart is created.
              """)
    public String createCart(
            @ToolParam(description = "{ line_items (optional): [{ product_id, quantity }] }", required = false)
            CreateCartParams params) {
        try {
            List<CartService.LineItemInput> inputs = null;
            if (params != null && params.lineItems() != null) {
                inputs = params.lineItems().stream()
                        .map(li -> new CartService.LineItemInput(li.productId(), li.quantity()))
                        .toList();
            }
            CartService.Cart cart = cartService.createCart(inputs);
            return buildCartResponse("create_cart", cart);
        } catch (Exception e) {
            return errorResponse("Failed to create cart: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "get_cart",
            description = "Get the current shopping cart contents including all items with images, quantities, prices in cents, and subtotal.")
    public String getCart(
            @ToolParam(description = "The cart ID returned by create_cart", required = true)
            String id) {
        try {
            CartService.Cart cart = cartService.getCart(id);
            if (cart == null) return errorResponse("Cart not found: " + id, "CART_NOT_FOUND");
            return buildCartResponse("get_cart", cart);
        } catch (Exception e) {
            return errorResponse("Failed to get cart: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "update_cart",
            description = """
              Update items in the shopping cart. Provide id (cart ID) and line_items array with \
              { product_id, quantity } for each item to update. Set quantity to 0 to remove an item. \
              Items not mentioned are left unchanged.
              """)
    public String updateCart(
            @ToolParam(description = "{ id (required): cart ID, line_items (required): [{ product_id, quantity }] }", required = true)
            UpdateCartParams params) {
        try {
            CartService.Cart cart = cartService.getCart(params.id());
            if (cart == null) return errorResponse("Cart not found: " + params.id(), "CART_NOT_FOUND");

            if (params.lineItems() != null) {
                for (LineItemInput li : params.lineItems()) {
                    if (li.productId() != null) {
                        cartService.updateItem(params.id(), li.productId(),
                                li.quantity() != null ? li.quantity() : 0);
                    }
                }
            }

            cart = cartService.getCart(params.id());
            return buildCartResponse("update_cart", cart);
        } catch (Exception e) {
            return errorResponse("Failed to update cart: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "cancel_cart",
            description = "Cancel a shopping cart. A canceled cart cannot be modified or checked out.")
    public String cancelCart(
            @ToolParam(description = "The cart ID to cancel", required = true)
            String id) {
        try {
            CartService.Cart cart = cartService.cancelCart(id);
            if (cart == null) return errorResponse("Cart not found: " + id, "CART_NOT_FOUND");
            return buildCartResponse("cancel_cart", cart);
        } catch (Exception e) {
            return errorResponse("Failed to cancel cart: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private String buildCartResponse(String tool, CartService.Cart cart) throws Exception {
        ObjectNode response = mapper.createObjectNode();

        ObjectNode ucp = mapper.createObjectNode();
        ucp.put("capability", "dev.ucp.shopping.cart");
        ucp.put("version", "2026-08-25");
        response.set("ucp", ucp);

        ObjectNode cartNode = mapper.createObjectNode();
        cartNode.put("id", cart.id());

        ArrayNode items = mapper.createArrayNode();
        long subtotal = 0;
        for (CartService.CartItem item : cart.items()) {
            ObjectNode itemNode = mapper.createObjectNode();
            itemNode.put("product_id", item.productId());
            itemNode.put("product_name", item.productName());
            itemNode.put("unit_price", item.unitPrice());
            itemNode.put("currency", item.currency());
            itemNode.put("quantity", item.quantity());
            if (item.imageUrl() != null && !item.imageUrl().isBlank()) {
                itemNode.put("image_url", item.imageUrl());
            }
            items.add(itemNode);
            subtotal += item.unitPrice() * item.quantity();
        }
        cartNode.set("line_items", items);

        cartNode.put("currency", "USD");
        ArrayNode totals = mapper.createArrayNode();
        totals.add(mapper.createObjectNode().put("type", "subtotal").put("amount", subtotal));
        totals.add(mapper.createObjectNode().put("type", "total").put("amount", subtotal));
        cartNode.set("totals", totals);

        cartNode.put("status", cart.status());
        response.set("cart", cartNode);

        return mapper.writeValueAsString(response);
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
