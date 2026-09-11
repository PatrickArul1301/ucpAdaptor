package com.accenture.UCPAdaptor.catalog;

import com.accenture.UCPAdaptor.catalog.model.Product;
import com.accenture.UCPAdaptor.catalog.model.SearchFilter;
import com.accenture.UCPAdaptor.catalog.model.SearchResult;

import java.util.Optional;

public interface CatalogPort {
    SearchResult search(SearchFilter filter, int limit, String cursor);
    Optional<Product> getProduct(String id);
}
