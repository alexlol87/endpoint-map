package com.acme.libs.reactive;

import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public class EmailNotifier implements Notifier {

    private final WebClient webClient = WebClient.create("https://api.sendgrid.com");

    @Override
    public Mono<Void> notify(String userId, String message) {
        return webClient.post().uri("/v3/mail/send")
                .bodyValue(Map.of("to", userId, "content", message))
                .retrieve().bodyToMono(Void.class);
    }
}
