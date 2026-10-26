package com.acme.recommendation;

import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/** Route handler injected by its interface: the routes name {@code TrendingHandler::top}, the work is in the impl. */
public interface TrendingHandler {

    Mono<ServerResponse> top(ServerRequest request);
}
