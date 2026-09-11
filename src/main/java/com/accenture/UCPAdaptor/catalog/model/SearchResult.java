package com.accenture.UCPAdaptor.catalog.model;

import java.util.List;

public record SearchResult(
    List<Product> products,
    int totalCount,
    boolean hasNextPage,
    String nextCursor
) {}
