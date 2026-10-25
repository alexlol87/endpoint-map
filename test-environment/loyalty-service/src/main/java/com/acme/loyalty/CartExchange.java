package com.acme.loyalty;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import reactor.core.publisher.Mono;

@HttpExchange("/carts")
public interface CartExchange {

    @GetExchange("/{userId}/points-preview")
    Mono<Map<String, Object>> pointsPreview(@PathVariable String userId);
}
