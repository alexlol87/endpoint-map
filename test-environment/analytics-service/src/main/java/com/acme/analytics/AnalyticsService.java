package com.acme.analytics;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Service
public class AnalyticsService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final ReactiveStringRedisTemplate redis;
    private final WebClient search;
    private final RestClient warehouse;

    public AnalyticsService(ReactiveStringRedisTemplate redis,
                            @Value("${clients.search}") String searchUrl,
                            @Value("${clients.warehouse}") String warehouseUrl) {
        this.redis = redis;
        this.search = WebClient.create(searchUrl);
        this.warehouse = RestClient.create(warehouseUrl);
    }

    public Mono<Void> track(Map<String, Object> event) {
        return Mono.just(event)
                .filter(e -> e.get("userId") != null)
                .flatMap(e -> redis.opsForList().leftPush("searches:" + e.get("userId"), String.valueOf(e.get("q"))))
                .then(redis.opsForValue().increment("events:count"))
                .then();
    }

    /** Flux.zip -> parallel; retryWhen -> retry badge; cycle back to search-service. */
    public Flux<Map<String, Object>> trending(String sku) {
        Flux<String> top = redis.opsForZSet().reverseRange("trending", org.springframework.data.domain.Range.closed(0L, 9L));
        Flux<Map<String, Object>> similar = sku == null
                ? Flux.empty()
                : search.get().uri("/search/products/{sku}/similar", sku).retrieve().bodyToFlux(MAP)
                        .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(50)));
        return Flux.zip(top, similar.defaultIfEmpty(Map.of()))
                .map(t -> Map.of("sku", t.getT1(), "similar", t.getT2()));
    }

    /** Blocking RestClient wrapped for a reactive pipeline. */
    public Mono<List<String>> searches(String userId) {
        return redis.opsForList().range("searches:" + userId, 0, 50)
                .collectList()
                .flatMap(list -> list.isEmpty()
                        ? Mono.fromCallable(() -> warehouse.get().uri("/warehouse/stock/hot").retrieve().body(String.class))
                                .subscribeOn(Schedulers.boundedElastic())
                                .map(List::of)
                        : Mono.just(list));
    }

    /** Self-recursion -> recursion guard / cycle marker. */
    public Flux<Map<String, Object>> exportAll(int page) {
        return search.get().uri("/search/products?page={p}", page).retrieve().bodyToFlux(MAP)
                .collectList()
                .flatMapMany(items -> items.isEmpty()
                        ? Flux.<Map<String, Object>>empty()
                        : Flux.fromIterable(items).concatWith(exportAll(page + 1)));
    }
}
