package com.acme.recommendation;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
public class TrendingHandlerImpl implements TrendingHandler {

    private final CatalogExchange catalog;

    public TrendingHandlerImpl(CatalogExchange catalog) {
        this.catalog = catalog;
    }

    public Mono<ServerResponse> topIn(String category) {
        return ServerResponse.ok().body(catalog.byCategory(category), Map.class);
    }

    @Override
    public Mono<ServerResponse> top(ServerRequest request) {
        return topIn(request.queryParam("category").orElse("trending"));
    }
}
