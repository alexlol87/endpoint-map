package com.acme.catalog.details;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AvailabilityService {

    private final StockLookupService stockLookupService;

    public AvailabilityService(StockLookupService stockLookupService) {
        this.stockLookupService = stockLookupService;
    }

    public Mono<Boolean> available(String sku) {
        return stockLookupService.stock(sku)
                .map(stock -> ((Number) stock.get("available")).intValue() > 0)
                .onErrorReturn(false);
    }
}
