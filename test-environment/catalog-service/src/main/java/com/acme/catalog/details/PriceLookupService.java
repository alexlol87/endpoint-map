package com.acme.catalog.details;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class PriceLookupService {

    private final WebClient pricingClient;

    public PriceLookupService(@Qualifier("pricingWebClient") WebClient pricingClient) {
        this.pricingClient = pricingClient;
    }

    /** Same util as StockLookupService, different WebClient bean -> pricing-service GET /prices/{sku}. */
    public Mono<Map<String, Object>> price(String sku) {
        return ReactiveClientUtils.getJson(pricingClient, "/prices/{sku}?currency=EUR", sku);
    }
}
