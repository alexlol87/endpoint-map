package com.acme.recommendation;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;

/** @HttpExchange client without type-level annotation. */
public interface ProfileExchange {

    @GetExchange("/profiles/{userId}")
    Mono<Map<String, Object>> getProfile(@PathVariable String userId);
}
