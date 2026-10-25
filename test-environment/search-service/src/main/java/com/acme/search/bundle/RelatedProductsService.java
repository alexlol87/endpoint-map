package com.acme.search.bundle;

import com.acme.libs.reactive.ReactiveHttp;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class RelatedProductsService {

    private final ReactiveHttp catalog;

    public RelatedProductsService(@Value("${search.catalog-url}") String catalogUrl) {
        this.catalog = new ReactiveHttp(WebClient.create(catalogUrl));
    }

    /** -> catalog-service GET /catalog/products/{sku}/details, itself a deep chain on the catalog side. */
    public Mono<Map> detailsOf(String sku) {
        return catalog.get(SearchPathUtils.productDetails(sku), Map.class);
    }
}
