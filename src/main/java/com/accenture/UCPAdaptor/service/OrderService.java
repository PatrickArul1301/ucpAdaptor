package com.accenture.UCPAdaptor.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {

    public record Order(
            String id,
            String checkoutId,
            List<CartService.CartItem> lineItems,
            long subtotal,
            long shippingCost,
            long tax,
            long total,
            String currency
    ) {}

    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final CheckoutService checkoutService;

    public OrderService(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostConstruct
    void init() {
        checkoutService.setOrderService(this);
    }

    public Order createOrder(String checkoutId, List<CartService.CartItem> lineItems,
                             long total, String currency) {
        CheckoutService.Checkout checkout = checkoutService.getCheckout(checkoutId);
        long subtotal = checkout != null ? checkout.subtotal() : 0L;
        long shippingCost = checkout != null ? checkout.shippingCost() : 0L;
        long tax = checkout != null ? checkout.tax() : 0L;

        String orderId = UUID.randomUUID().toString();
        Order order = new Order(orderId, checkoutId, List.copyOf(lineItems),
                subtotal, shippingCost, tax, total, currency);
        orders.put(orderId, order);
        return order;
    }

    public Order getOrder(String orderId) {
        return orders.get(orderId);
    }
}
