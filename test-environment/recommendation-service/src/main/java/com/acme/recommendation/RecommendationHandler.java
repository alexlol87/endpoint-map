package com.acme.recommendation;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class RecommendationHandler {

    private final ProfileExchange profiles;
    private final CatalogExchange catalog;

    public RecommendationHandler(ProfileExchange profiles, CatalogExchange catalog) {
        this.profiles = profiles;
        this.catalog = catalog;
    }

    public Mono<ServerResponse> forUser(ServerRequest request) {
        String userId = request.pathVariable("userId");
        Flux<Map<String, Object>> recs = profiles.getProfile(userId)
                .flatMapMany(p -> Flux.fromIterable(((Map<?, ?>) p.getOrDefault("interests", Map.of())).keySet()))
                .flatMap(interest -> catalog.byCategory(String.valueOf(interest)))
                .take(20);
        return ServerResponse.ok().body(recs, Map.class);
    }

    public Mono<ServerResponse> feedback(ServerRequest request) {
        return request.bodyToMono(Map.class).flatMap(b -> ServerResponse.accepted().build());
    }

    public Mono<ServerResponse> popular(ServerRequest request) {
        return ServerResponse.ok().body(catalog.byCategory("popular"), Map.class);
    }

    public Mono<ServerResponse> clearCache(ServerRequest request) {
        return ServerResponse.noContent().build();
    }

    public Mono<ServerResponse> retrain(String model) {
        return ServerResponse.accepted().bodyValue(Map.of("model", model));
    }

    /** Mono.when -> parallel. */
    public Mono<ServerResponse> bundle(ServerRequest request) {
        String userId = request.pathVariable("userId");
        return Mono.when(profiles.getProfile(userId), catalog.byCategory("bundles").collectList())
                .then(ServerResponse.ok().build());
    }
}
