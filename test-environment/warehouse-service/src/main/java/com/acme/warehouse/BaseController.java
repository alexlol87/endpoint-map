package com.acme.warehouse;

import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Mono;

/** Mappings declared on an abstract base class are inherited by every controller that extends it. */
public abstract class BaseController {

    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("UP");
    }
}
