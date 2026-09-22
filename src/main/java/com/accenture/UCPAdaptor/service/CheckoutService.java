package com.accenture.UCPAdaptor.service;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CheckoutService {

    private static final double SHIPPING_COST = 9.99;
    private static final double TAX_RATE = 0.08875;

    public record Address(
            String firstName, String lastName,
            String line1, String line2,
            String city, String state,
            String postalCode, String country
    ) {}

    public record Checkout(
            String checkoutId, String cartId,
            Address shippingAddress, String status,
            double subtotal, double shippingCost, double tax, double total,
            String currency
    ) {}

    private final ConcurrentHashMap<String, Checkout> checkouts = new ConcurrentHashMap<>();
    private final CartService cartService;

    public CheckoutService(CartService cartService) {
        this.cartService = cartService;
    }

    public Checkout createCheckout(String cartId, Address address) {
        CartService.Cart cart = cartService.getCart(cartId);
        if (cart == null) throw new IllegalArgumentException("CART_NOT_FOUND");

        double subtotal = cart.items().stream()
                .mapToDouble(i -> i.unitPrice() * i.quantity())
                .sum();
        subtotal = Math.round(subtotal * 100.0) / 100.0;
        double tax = Math.round(subtotal * TAX_RATE * 100.0) / 100.0;
        double total = Math.round((subtotal + SHIPPING_COST + tax) * 100.0) / 100.0;

        String checkoutId = UUID.randomUUID().toString();
        Checkout checkout = new Checkout(checkoutId, cartId, address, "PENDING",
                subtotal, SHIPPING_COST, tax, total, "USD");
        checkouts.put(checkoutId, checkout);
        return checkout;
    }

    public Checkout getCheckout(String checkoutId) {
        return checkouts.get(checkoutId);
    }

    public Checkout updateCheckout(String checkoutId, Address address) {
        Checkout existing = checkouts.get(checkoutId);
        if (existing == null) return null;
        Checkout updated = new Checkout(
                existing.checkoutId(), existing.cartId(), address, existing.status(),
                existing.subtotal(), existing.shippingCost(), existing.tax(), existing.total(),
                existing.currency());
        checkouts.put(checkoutId, updated);
        return updated;
    }

    public Checkout confirmCheckout(String checkoutId) {
        Checkout existing = checkouts.get(checkoutId);
        if (existing == null) return null;
        Checkout confirmed = new Checkout(
                existing.checkoutId(), existing.cartId(), existing.shippingAddress(), "CONFIRMED",
                existing.subtotal(), existing.shippingCost(), existing.tax(), existing.total(),
                existing.currency());
        checkouts.put(checkoutId, confirmed);
        cartService.markCheckedOut(existing.cartId());
        return confirmed;
    }
}
