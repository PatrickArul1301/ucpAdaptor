package com.accenture.UCPAdaptor.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CartService {

    public record CartItem(String productId, String productName, long unitPrice, String currency, int quantity, String imageUrl) {}

    public record Cart(String id, List<CartItem> items, String status) {}

    private final ConcurrentHashMap<String, List<CartItem>> cartItems = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> cartStatuses = new ConcurrentHashMap<>();
    private final ProductCatalogService catalogService;

    public CartService(ProductCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public Cart createCart(List<LineItemInput> lineItems) {
        String id = UUID.randomUUID().toString();
        cartItems.put(id, new ArrayList<>());
        cartStatuses.put(id, "active");

        if (lineItems != null) {
            for (LineItemInput li : lineItems) {
                if (li.productId() != null && li.quantity() != null && li.quantity() > 0) {
                    try {
                        addItem(id, li.productId(), li.quantity());
                    } catch (Exception ignored) {
                        // skip invalid items silently during cart creation
                    }
                }
            }
        }

        List<CartItem> items = cartItems.get(id);
        return new Cart(id, List.copyOf(items), "active");
    }

    public record LineItemInput(String productId, Integer quantity) {}

    public Cart getCart(String cartId) {
        List<CartItem> items = cartItems.get(cartId);
        if (items == null) return null;
        return new Cart(cartId, List.copyOf(items), cartStatuses.getOrDefault(cartId, "active"));
    }

    public Cart addItem(String cartId, String productId, int quantity) {
        ProductCatalogService.Product product = catalogService.getAll().stream()
                .filter(p -> p.id().equals(productId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));

        List<CartItem> items = cartItems.get(cartId);
        if (items == null) throw new IllegalArgumentException("CART_NOT_FOUND");
        if ("checked_out".equals(cartStatuses.get(cartId)) || "canceled".equals(cartStatuses.get(cartId))) {
            throw new IllegalStateException("CART_NOT_ACTIVE");
        }

        String currency = product.currency() != null && !product.currency().isBlank() ? product.currency() : "USD";
        long unitPriceCents = Math.round(product.price() * 100);

        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).productId().equals(productId)) {
                CartItem existing = items.get(i);
                items.set(i, new CartItem(productId, existing.productName(), existing.unitPrice(),
                        existing.currency(), existing.quantity() + quantity, existing.imageUrl()));
                found = true;
                break;
            }
        }
        if (!found) {
            items.add(new CartItem(productId, product.name(), unitPriceCents, currency, quantity, product.imageUrl()));
        }

        return new Cart(cartId, List.copyOf(items), cartStatuses.get(cartId));
    }

    public Cart updateItem(String cartId, String productId, int quantity) {
        List<CartItem> items = cartItems.get(cartId);
        if (items == null) return null;
        if (quantity <= 0) {
            items.removeIf(i -> i.productId().equals(productId));
        } else {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).productId().equals(productId)) {
                    CartItem existing = items.get(i);
                    items.set(i, new CartItem(productId, existing.productName(), existing.unitPrice(),
                            existing.currency(), quantity, existing.imageUrl()));
                    break;
                }
            }
        }
        return new Cart(cartId, List.copyOf(items), cartStatuses.getOrDefault(cartId, "active"));
    }

    public Cart cancelCart(String cartId) {
        if (!cartItems.containsKey(cartId)) return null;
        cartStatuses.put(cartId, "canceled");
        List<CartItem> items = cartItems.get(cartId);
        return new Cart(cartId, List.copyOf(items), "canceled");
    }

    public void markCheckedOut(String cartId) {
        cartStatuses.put(cartId, "checked_out");
    }
}
