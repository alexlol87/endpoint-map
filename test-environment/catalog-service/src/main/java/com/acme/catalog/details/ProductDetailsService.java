package com.acme.catalog.details;

import com.acme.catalog.ProductRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ProductDetailsService {

    private final ProductRepository products;
    private final AvailabilityService availabilityService;
    private final PriceLookupService priceLookupService;

    public ProductDetailsService(ProductRepository products, AvailabilityService availabilityService,
                                 PriceLookupService priceLookupService) {
        this.products = products;
        this.availabilityService = availabilityService;
        this.priceLookupService = priceLookupService;
    }

    public Mono<Map<String, Object>> details(String sku) {
        return products.findBySku(sku)
                .flatMap(p -> Mono.zip(availabilityService.available(sku), priceLookupService.price(sku))
                        .map(t -> Map.<String, Object>of("product", p, "available", t.getT1(), "price", t.getT2())));
    }
}
