package com.accenture.UCPAdaptor.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CartService {

    public record CartItem(String productId, String productName, double unitPrice, String currency, int quantity) {}

    public record Cart(String cartId, List<CartItem> items, String status) {}

    private final ConcurrentHashMap<String, List<CartItem>> cartItems = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> cartStatuses = new ConcurrentHashMap<>();
    private final ProductCatalogService catalogService;

    public CartService(ProductCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public Cart createCart() {
        String id = UUID.randomUUID().toString();
        cartItems.put(id, new ArrayList<>());
        cartStatuses.put(id, "ACTIVE");
        return new Cart(id, List.of(), "ACTIVE");
    }

    public Cart getCart(String cartId) {
        List<CartItem> items = cartItems.get(cartId);
        if (items == null) return null;
        return new Cart(cartId, List.copyOf(items), cartStatuses.getOrDefault(cartId, "ACTIVE"));
    }

    public Cart addItem(String cartId, String productId, int quantity) {
        ProductCatalogService.Product product = catalogService.getAll().stream()
                .filter(p -> p.id().equals(productId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));

        String id;
        if (cartId == null || cartId.isBlank()) {
            id = UUID.randomUUID().toString();
            cartItems.put(id, new ArrayList<>());
            cartStatuses.put(id, "ACTIVE");
        } else {
            id = cartId;
            if (!cartItems.containsKey(id)) {
                throw new IllegalArgumentException("CART_NOT_FOUND");
            }
            if ("CHECKED_OUT".equals(cartStatuses.get(id))) {
                throw new IllegalStateException("CART_CHECKED_OUT");
            }
        }

        List<CartItem> items = cartItems.get(id);
        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).productId().equals(productId)) {
                CartItem existing = items.get(i);
                items.set(i, new CartItem(productId, existing.productName(), existing.unitPrice(),
                        existing.currency(), existing.quantity() + quantity));
                found = true;
                break;
            }
        }
        if (!found) {
            String currency = product.currency() != null && !product.currency().isBlank() ? product.currency() : "USD";
            items.add(new CartItem(productId, product.name(), product.price(), currency, quantity));
        }

        return new Cart(id, List.copyOf(items), cartStatuses.get(id));
    }

    public Cart removeItem(String cartId, String productId) {
        List<CartItem> items = cartItems.get(cartId);
        if (items == null) return null;
        items.removeIf(i -> i.productId().equals(productId));
        return new Cart(cartId, List.copyOf(items), cartStatuses.getOrDefault(cartId, "ACTIVE"));
    }

    public Cart updateItem(String cartId, String productId, int quantity) {
        List<CartItem> items = cartItems.get(cartId);
        if (items == null) return null;
        if (quantity <= 0) {
            return removeItem(cartId, productId);
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).productId().equals(productId)) {
                CartItem existing = items.get(i);
                items.set(i, new CartItem(productId, existing.productName(), existing.unitPrice(),
                        existing.currency(), quantity));
                break;
            }
        }
        return new Cart(cartId, List.copyOf(items), cartStatuses.getOrDefault(cartId, "ACTIVE"));
    }

    public void markCheckedOut(String cartId) {
        cartStatuses.put(cartId, "CHECKED_OUT");
    }
}
