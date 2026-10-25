package com.acme.catalog.details;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class StockLookupService {

    private final WebClient inventoryClient;

    public StockLookupService(@Qualifier("inventoryWebClient") WebClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    /** -> inventory-service GET /inventory/{sku}, made inside the util. */
    public Mono<Map<String, Object>> stock(String sku) {
        return ReactiveClientUtils.getJson(inventoryClient, "/inventory/{sku}", sku);
    }
}
