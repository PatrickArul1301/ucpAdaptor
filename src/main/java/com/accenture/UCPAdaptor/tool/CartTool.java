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

@Component
public class CartTool {

    private final CartService cartService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CartTool(CartService cartService) {
        this.cartService = cartService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddToCartParams(
            @JsonProperty("cart_id") String cartId,
            @JsonProperty("product_id") String productId,
            @JsonProperty("quantity") Integer quantity
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CartItemParams(
            @JsonProperty("cart_id") String cartId,
            @JsonProperty("product_id") String productId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateItemParams(
            @JsonProperty("cart_id") String cartId,
            @JsonProperty("product_id") String productId,
            @JsonProperty("quantity") Integer quantity
    ) {}

    @Tool(name = "add_to_cart",
            description = """
              Add a product to the shopping cart. Provide product_id (exact SKU from search_catalog) \
              and quantity. Omit cart_id to create a new cart; supply an existing cart_id to add to it. \
              Returns the updated cart contents including subtotal.
              """)
    public String addToCart(
            @ToolParam(description = "{ cart_id (optional, omit to create new cart), product_id (required), quantity (required, positive integer) }", required = true)
            AddToCartParams params) {
        try {
            CartService.Cart cart = cartService.addItem(
                    params.cartId(),
                    params.productId(),
                    params.quantity() != null ? params.quantity() : 1);
            return buildCartResponse("add_to_cart", cart);
        } catch (IllegalArgumentException e) {
            return errorResponse(e.getMessage().equals("PRODUCT_NOT_FOUND")
                    ? "Product not found in catalog: " + params.productId()
                    : "Cart not found: " + params.cartId(), e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse("Cart has already been checked out", "CART_CHECKED_OUT");
        } catch (Exception e) {
            return errorResponse("Failed to add item to cart: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "get_cart",
            description = "Get the current shopping cart contents including all items, quantities, prices, and subtotal.")
    public String getCart(
            @ToolParam(description = "The cart ID returned by add_to_cart", required = true)
            String cartId) {
        try {
            CartService.Cart cart = cartService.getCart(cartId);
            if (cart == null) return errorResponse("Cart not found: " + cartId, "CART_NOT_FOUND");
            return buildCartResponse("get_cart", cart);
        } catch (Exception e) {
            return errorResponse("Failed to get cart: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "remove_from_cart",
            description = "Remove a product entirely from the shopping cart.")
    public String removeFromCart(
            @ToolParam(description = "{ cart_id, product_id } — both required", required = true)
            CartItemParams params) {
        try {
            CartService.Cart cart = cartService.removeItem(params.cartId(), params.productId());
            if (cart == null) return errorResponse("Cart not found: " + params.cartId(), "CART_NOT_FOUND");
            return buildCartResponse("remove_from_cart", cart);
        } catch (Exception e) {
            return errorResponse("Failed to remove item: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "update_cart_item",
            description = "Update the quantity for a product already in the cart. Set quantity to 0 to remove the item.")
    public String updateCartItem(
            @ToolParam(description = "{ cart_id, product_id, quantity } — all required", required = true)
            UpdateItemParams params) {
        try {
            CartService.Cart cart = cartService.updateItem(
                    params.cartId(), params.productId(),
                    params.quantity() != null ? params.quantity() : 0);
            if (cart == null) return errorResponse("Cart not found: " + params.cartId(), "CART_NOT_FOUND");
            return buildCartResponse("update_cart_item", cart);
        } catch (Exception e) {
            return errorResponse("Failed to update cart item: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private String buildCartResponse(String capability, CartService.Cart cart) throws Exception {
        ObjectNode response = mapper.createObjectNode();

        ObjectNode ucp = mapper.createObjectNode();
        ucp.put("capability", "dev.ucp.shopping.cart." + capability);
        ucp.put("version", "2026-08-25");
        response.set("ucp", ucp);

        ObjectNode cartNode = mapper.createObjectNode();
        cartNode.put("cart_id", cart.cartId());

        ArrayNode items = mapper.createArrayNode();
        double subtotal = 0;
        for (CartService.CartItem item : cart.items()) {
            ObjectNode itemNode = mapper.createObjectNode();
            itemNode.put("product_id", item.productId());
            itemNode.put("product_name", item.productName());
            itemNode.put("unit_price", item.unitPrice());
            itemNode.put("currency_code", item.currency());
            itemNode.put("quantity", item.quantity());
            items.add(itemNode);
            subtotal += item.unitPrice() * item.quantity();
        }
        cartNode.set("items", items);

        ObjectNode subtotalNode = mapper.createObjectNode();
        subtotalNode.put("amount", Math.round(subtotal * 100.0) / 100.0);
        subtotalNode.put("currency_code", "USD");
        cartNode.set("subtotal", subtotalNode);

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
