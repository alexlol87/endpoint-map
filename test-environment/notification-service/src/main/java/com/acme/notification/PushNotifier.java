package com.acme.notification;

import com.acme.libs.reactive.Notifier;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;

@Primary
@Component
public class PushNotifier implements Notifier {

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public Mono<Void> notify(String userId, String message) {
        restTemplate.postForObject("https://fcm.googleapis.com/v1/projects/acme/messages:send", Map.of("to", userId, "body", message), String.class);
        return Mono.empty();
    }
}
