package com.acme.search;

import com.acme.libs.reactive.ReactiveHttp;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
public class SearchHandler {

    private final ReactiveHttp catalog;
    private final ReactiveHttp analytics;

    public SearchHandler(@Value("${search.catalog-url}") String catalogUrl,
                         @Value("${search.analytics-url}") String analyticsUrl) {
        this.catalog = new ReactiveHttp(WebClient.create(catalogUrl));
        this.analytics = new ReactiveHttp(WebClient.create(analyticsUrl));
    }

    public Mono<ServerResponse> search(ServerRequest request) {
        String q = request.queryParam("q").orElse("");
        Mono<Void> track = analytics.post("/analytics/events", Map.of("type", "search", "q", q), Void.class);
        return track.then(ServerResponse.ok().body(catalog.getMany("/catalog/products?category={c}", Map.class, q), Map.class));
    }

    public Mono<ServerResponse> products(ServerRequest request) {
        return ServerResponse.ok().body(catalog.getMany("/catalog/products", Map.class), Map.class);
    }

    public Mono<ServerResponse> productBySku(ServerRequest request) {
        return catalog.get("/catalog/products/" + request.pathVariable("sku"), Map.class)
                .flatMap(p -> ServerResponse.ok().bodyValue(p))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    /** search -> analytics GET /analytics/trending -> search GET /search/products/{sku}/similar: cycle. */
    public Mono<ServerResponse> similar(ServerRequest request) {
        return analytics.get("/analytics/trending?sku=" + request.pathVariable("sku"), Map.class)
                .flatMap(t -> ServerResponse.ok().bodyValue(t));
    }

    public Mono<ServerResponse> suggest(ServerRequest request) {
        return ServerResponse.ok().bodyValue(Map.of("suggestions", new String[0]));
    }

    public Mono<ServerResponse> history(ServerRequest request) {
        return analytics.get("/analytics/users/" + request.pathVariable("userId") + "/searches", Map.class)
                .flatMap(h -> ServerResponse.ok().bodyValue(h));
    }

    public Mono<ServerResponse> reindex(ServerRequest request) {
        return catalog.getMany("/catalog/products", Map.class).count()
                .flatMap(n -> ServerResponse.accepted().bodyValue(Map.of("indexed", n)));
    }

    public Mono<ServerResponse> dropIndex(ServerRequest request) {
        return ServerResponse.noContent().build();
    }

    public Mono<ServerResponse> facet(ServerRequest request) {
        return ServerResponse.ok().bodyValue(Map.of("facet", request.path()));
    }
}
