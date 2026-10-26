package com.acme.review.moderation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Service
public class StockSync {

    private final WebClient stock;

    public StockSync(@Qualifier("stockClient") WebClient stock) {
        this.stock = stock;
    }

    /** .patch() + UriComponentsBuilder -> inventory-service PATCH /inventory/{sku}/quantity. */
    public Mono<Void> release(String sku) {
        String uri = UriComponentsBuilder.fromPath("/inventory").pathSegment(sku, "quantity")
                .queryParam("delta", 1).build().toUriString();
        return stock.patch().uri(uri).retrieve().bodyToMono(Void.class);
    }
}
