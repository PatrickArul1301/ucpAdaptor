package com.accenture.UCPAdaptor.service;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CheckoutService {

    private static final long SHIPPING_COST_CENTS = 999;
    private static final double TAX_RATE = 0.08875;

    public record Buyer(String email, String firstName, String lastName) {}

    public record Address(
            String firstName, String lastName,
            String line1, String line2,
            String city, String state,
            String postalCode, String country
    ) {}

    public record Checkout(
            String id, String cartId,
            Buyer buyer, Address shippingAddress,
            String status,
            long subtotal, long shippingCost, long tax, long total,
            String currency, String orderId
    ) {}

    private final ConcurrentHashMap<String, Checkout> checkouts = new ConcurrentHashMap<>();
    private final CartService cartService;
    private OrderService orderService;

    public CheckoutService(CartService cartService) {
        this.cartService = cartService;
    }

    // Setter injection to break circular dependency
    public void setOrderService(OrderService orderService) {
        this.orderService = orderService;
    }

    public Checkout createCheckout(String cartId, Buyer buyer, Address address) {
        CartService.Cart cart = cartService.getCart(cartId);
        if (cart == null) throw new IllegalArgumentException("CART_NOT_FOUND");

        long subtotal = cart.items().stream()
                .mapToLong(i -> i.unitPrice() * i.quantity())
                .sum();
        long tax = Math.round(subtotal * TAX_RATE);
        long total = subtotal + SHIPPING_COST_CENTS + tax;

        String checkoutId = UUID.randomUUID().toString();
        String status = computeStatus(buyer, address);
        Checkout checkout = new Checkout(checkoutId, cartId, buyer, address, status,
                subtotal, SHIPPING_COST_CENTS, tax, total, "USD", null);
        checkouts.put(checkoutId, checkout);
        return checkout;
    }

    public Checkout getCheckout(String checkoutId) {
        return checkouts.get(checkoutId);
    }

    public Checkout updateCheckout(String checkoutId, Buyer buyer, Address address) {
        Checkout existing = checkouts.get(checkoutId);
        if (existing == null) return null;

        Buyer mergedBuyer = mergeBuyer(existing.buyer(), buyer);
        Address mergedAddress = address != null ? address : existing.shippingAddress();
        String status = computeStatus(mergedBuyer, mergedAddress);

        Checkout updated = new Checkout(
                existing.id(), existing.cartId(), mergedBuyer, mergedAddress, status,
                existing.subtotal(), existing.shippingCost(), existing.tax(), existing.total(),
                existing.currency(), existing.orderId());
        checkouts.put(checkoutId, updated);
        return updated;
    }

    public Checkout completeCheckout(String checkoutId) {
        Checkout existing = checkouts.get(checkoutId);
        if (existing == null) return null;

        CartService.Cart cart = cartService.getCart(existing.cartId());
        String orderId = null;
        if (orderService != null && cart != null) {
            OrderService.Order order = orderService.createOrder(
                    checkoutId, cart.items(), existing.total(), existing.currency());
            orderId = order.id();
        }

        Checkout completed = new Checkout(
                existing.id(), existing.cartId(), existing.buyer(), existing.shippingAddress(), "completed",
                existing.subtotal(), existing.shippingCost(), existing.tax(), existing.total(),
                existing.currency(), orderId);
        checkouts.put(checkoutId, completed);
        cartService.markCheckedOut(existing.cartId());
        return completed;
    }

    public Checkout cancelCheckout(String checkoutId) {
        Checkout existing = checkouts.get(checkoutId);
        if (existing == null) return null;
        Checkout canceled = new Checkout(
                existing.id(), existing.cartId(), existing.buyer(), existing.shippingAddress(), "canceled",
                existing.subtotal(), existing.shippingCost(), existing.tax(), existing.total(),
                existing.currency(), existing.orderId());
        checkouts.put(checkoutId, canceled);
        return canceled;
    }

    private String computeStatus(Buyer buyer, Address address) {
        boolean hasBuyerEmail = buyer != null && buyer.email() != null && !buyer.email().isBlank();
        boolean hasAddress = address != null && address.line1() != null && !address.line1().isBlank()
                && address.city() != null && address.postalCode() != null;
        if (hasBuyerEmail && hasAddress) return "ready_for_complete";
        return "incomplete";
    }

    private Buyer mergeBuyer(Buyer existing, Buyer incoming) {
        if (incoming == null) return existing;
        if (existing == null) return incoming;
        return new Buyer(
                incoming.email() != null ? incoming.email() : existing.email(),
                incoming.firstName() != null ? incoming.firstName() : existing.firstName(),
                incoming.lastName() != null ? incoming.lastName() : existing.lastName()
        );
    }
}
