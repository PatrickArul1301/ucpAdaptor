package com.accenture.UCPAdaptor.catalog.model;

public record CartItem(
    String productId,
    String productName,
    int quantity,
    int unitPriceMinorUnits,
    String currency
) {}
