package com.accenture.UCPAdaptor.catalog.model;

import java.util.List;

public record Product(
    String id,
    String name,
    String description,
    List<String> categories,
    int priceMinorUnits,
    String currency,
    boolean inStock
) {}
