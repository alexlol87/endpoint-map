package com.acme.libs.reactive;

import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public class SmsNotifier implements Notifier {

    private final WebClient webClient;

    public SmsNotifier(String notificationBaseUrl) {
        this.webClient = WebClient.builder().baseUrl(notificationBaseUrl).build();
    }

    @Override
    public Mono<Void> notify(String userId, String message) {
        return webClient.post().uri("/notifications/sms")
                .bodyValue(Map.of("userId", userId, "text", message))
                .retrieve().bodyToMono(Void.class);
    }
}
