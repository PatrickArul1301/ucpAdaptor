package com.accenture.UCPAdaptor.catalog.model;

import java.util.List;

public record CheckoutSession(
    String id,
    String cartId,
    List<CartItem> items,
    String buyerEmail,
    String shippingAddress,
    String status,
    String orderId
) {}
