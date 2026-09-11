package com.accenture.UCPAdaptor.catalog.model;

import java.util.List;

public record SearchFilter(
    String query,
    List<String> categories,
    Integer priceMin,
    Integer priceMax
) {}
