package com.acme.recommendation;

import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import reactor.core.publisher.Flux;

@HttpExchange(url = "/catalog")
public interface CatalogExchange {

    @GetExchange("/products")
    Flux<Map<String, Object>> byCategory(@RequestParam("category") String category);
}
