package com.acme.review.moderation;

import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ModerationHandler {

    private final ModerationService service;

    public ModerationHandler(ModerationService service) {
        this.service = service;
    }

    public Mono<Void> moderate(long id, Map<String, Object> body) {
        return service.moderate(id, String.valueOf(body.get("userId")), String.valueOf(body.get("sku")), body);
    }
}
