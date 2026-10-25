package com.acme.loyalty;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Annotated endpoints living next to functional ones (LoyaltyRoutes) in the same service. */
@RestController
@RequestMapping("/loyalty/{userId}")
public class LoyaltyController {

    private final LoyaltyService service;

    public LoyaltyController(LoyaltyService service) {
        this.service = service;
    }

    @GetMapping("/balance")
    public Mono<Map<String, Object>> balance(@PathVariable String userId) {
        return service.balance(userId);
    }

    @GetMapping("/tier")
    public Mono<Map<String, Object>> tier(@PathVariable String userId) {
        return service.tier(userId);
    }
}
