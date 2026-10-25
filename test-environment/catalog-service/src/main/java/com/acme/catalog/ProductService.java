package com.acme.catalog;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ProductService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final ProductRepository products;
    private final WebClient pricingClient;
    private final WebClient inventoryClient;

    public ProductService(ProductRepository products,
                          @Qualifier("pricingWebClient") WebClient pricingClient,
                          @Qualifier("inventoryWebClient") WebClient inventoryClient) {
        this.products = products;
        this.pricingClient = pricingClient;
        this.inventoryClient = inventoryClient;
    }

    public Flux<Product> list(String category) {
        return category == null ? products.findAll() : products.findByCategory(category);
    }

    public Mono<Map<String, Object>> getWithPrice(String sku) {
        return products.findBySku(sku)
                .flatMap(p -> pricingClient.get().uri("/prices/{sku}?currency=EUR", sku).retrieve().bodyToMono(MAP)
                        .map(price -> Map.<String, Object>of("product", p, "price", price)))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("unknown sku " + sku)));
    }

    /** flatMap over a Flux -> loop; concatMap -> loop. */
    public Flux<Product> batch(Flux<String> skus) {
        return skus
                .flatMap(products::findBySku)
                .concatMap(p -> inventoryClient.get().uri("/inventory/{sku}", p.sku()).retrieve().bodyToMono(MAP)
                        .filter(stock -> ((Number) stock.get("available")).intValue() > 0)
                        .map(stock -> p))
                .flatMap(products::save);
    }
}
