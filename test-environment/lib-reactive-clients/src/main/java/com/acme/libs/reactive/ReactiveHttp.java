package com.acme.libs.reactive;

import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** WebClient wrapper. Base URL comes from the WebClient the service passes in; path is a parameter. */
public class ReactiveHttp {

    private final WebClient webClient;

    public ReactiveHttp(WebClient webClient) {
        this.webClient = webClient;
    }

    public ReactiveHttp(String baseUrl) {
        this(WebClient.create(baseUrl));
    }

    public <T> Mono<T> get(String path, Class<T> type) {
        return webClient.get().uri(path).retrieve().bodyToMono(type);
    }

    public <T> Flux<T> getMany(String path, Class<T> type, Object... vars) {
        return webClient.get().uri(path, vars).retrieve().bodyToFlux(type);
    }

    public <T> Mono<T> post(String path, Object body, Class<T> type) {
        return webClient.post().uri(path).bodyValue(body).retrieve().bodyToMono(type);
    }

    public Mono<Void> send(HttpMethod method, String path) {
        return webClient.method(method).uri(path).retrieve().bodyToMono(Void.class);
    }
}
