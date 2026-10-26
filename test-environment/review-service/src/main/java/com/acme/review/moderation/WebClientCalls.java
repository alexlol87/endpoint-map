package com.acme.review.moderation;

import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Static util: client, verb and URI all come from the caller. */
public final class WebClientCalls {

    private WebClientCalls() {
    }

    public static Mono<Void> send(WebClient client, HttpMethod method, String uri) {
        return client.method(method).uri(uri).retrieve().bodyToMono(Void.class);
    }
}
