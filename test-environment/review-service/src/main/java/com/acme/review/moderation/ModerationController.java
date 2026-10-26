package com.acme.review.moderation;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Deep chain: controller -> handler -> service -> sync services -> static utils doing the WebClient/RestClient calls. */
@RestController
public class ModerationController {

    private final ModerationHandler handler;

    public ModerationController(ModerationHandler handler) {
        this.handler = handler;
    }

    @PostMapping("/reviews/{id}/moderate")
    public Mono<Void> moderate(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return handler.moderate(id, body);
    }
}
