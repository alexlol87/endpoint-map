package com.acme.search.bundle;

import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class BundleService {

    private final RelatedProductsService relatedProducts;

    public BundleService(RelatedProductsService relatedProducts) {
        this.relatedProducts = relatedProducts;
    }

    public Mono<Map<String, Object>> bundle(String sku) {
        return relatedProducts.detailsOf(sku)
                .map(details -> Map.<String, Object>of("sku", sku, "items", details));
    }
}
