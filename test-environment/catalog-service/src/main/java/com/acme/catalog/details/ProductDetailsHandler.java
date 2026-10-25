package com.acme.catalog.details;

import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Handler called from an annotated controller (not a functional HandlerFunction). */
@Component
public class ProductDetailsHandler {

    private final ProductDetailsService detailsService;

    public ProductDetailsHandler(ProductDetailsService detailsService) {
        this.detailsService = detailsService;
    }

    public Mono<Map<String, Object>> details(String sku) {
        return detailsService.details(sku.trim())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("unknown sku " + sku)));
    }
}
