package com.acme.cart;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class CartService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final ReactiveRedisTemplate<String, String> redis;
    private final WebClient catalog;
    private final WebClient loyalty;

    public CartService(ReactiveRedisTemplate<String, String> redis,
                       @Value("${services.catalog}") String catalogUrl,
                       @Value("${services.loyalty}") String loyaltyUrl) {
        this.redis = redis;
        this.catalog = WebClient.create(catalogUrl);
        this.loyalty = WebClient.builder().baseUrl(loyaltyUrl).build();
    }

    /** Mono.zip -> parallel; switchIfEmpty / defaultIfEmpty -> branches. */
    public Mono<Map<String, Object>> view(String userId) {
        Mono<Map<Object, Object>> items = redis.opsForHash().entries("cart:" + userId)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .switchIfEmpty(Mono.just(Map.of()));
        Mono<Map<String, Object>> points = loyalty.get().uri("/loyalty/{userId}/balance", userId).retrieve().bodyToMono(MAP)
                .defaultIfEmpty(Map.of("points", 0));
        return Mono.zip(items, points)
                .map(t -> Map.of("items", t.getT1(), "points", t.getT2()));
    }

    public Mono<Boolean> add(String userId, String sku) {
        return catalog.get().uri("/catalog/products/" + sku).retrieve().bodyToMono(MAP)
                .flatMap(p -> redis.opsForHash().put("cart:" + userId, sku, String.valueOf(p.get("price"))))
                .flatMap(ok -> redis.expire("cart:" + userId, Duration.ofDays(7)))
                .onErrorReturn(false);
    }

    public Mono<Boolean> clear(String userId) {
        return redis.delete("cart:" + userId).map(n -> n > 0);
    }

    public Mono<Map<String, Object>> pointsPreview(String userId) {
        return redis.opsForValue().get("points-preview:" + userId)
                .map(v -> Map.<String, Object>of("points", v))
                .switchIfEmpty(loyalty.post().uri("/loyalty/{userId}/simulate", userId).retrieve().bodyToMono(MAP));
    }
}
