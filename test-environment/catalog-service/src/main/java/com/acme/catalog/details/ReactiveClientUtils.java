package com.acme.catalog.details;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Static util that makes the HTTP call. The client and the path are parameters, so the target
 * depends on the caller: inventory-service from StockLookupService, pricing-service from PriceLookupService.
 */
public final class ReactiveClientUtils {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private ReactiveClientUtils() {
    }

    public static Mono<Map<String, Object>> getJson(WebClient client, String uri, Object... vars) {
        return client.get().uri(uri, vars).retrieve().bodyToMono(MAP);
    }
}
