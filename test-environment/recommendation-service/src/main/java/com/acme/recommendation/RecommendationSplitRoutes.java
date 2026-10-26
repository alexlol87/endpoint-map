package com.acme.recommendation;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;
import static org.springframework.web.reactive.function.server.RouterFunctions.nest;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * One router split into parts, the way larger services grow it:
 *   GET  /recs/split/users/{userId}   local path + a helper (with its own local predicate) that fills the builder
 *   GET  /recs/split/trending         method reference supplying the nested routes; handler injected by interface
 *   POST /recs/split/feedback         nest(POST(..), ..): the nested route takes the outer verb
 */
@Configuration
public class RecommendationSplitRoutes {

    private final TrendingHandler trending;

    public RecommendationSplitRoutes(TrendingHandler trending) {
        this.trending = trending;
    }

    @Bean
    public RouterFunction<ServerResponse> splitRoutes(RecommendationHandler handler) {
        RequestPredicate json = accept(MediaType.APPLICATION_JSON);
        String base = "/recs/split";
        return route()
                .path(base, b -> userRoutes(b, handler))
                .nest(path(base + "/trending").and(json), this::trendingRoutes)
                .add(nest(POST(base), route(path("/feedback"), handler::feedback)))
                .build();
    }

    private RouterFunctions.Builder userRoutes(RouterFunctions.Builder b, RecommendationHandler handler) {
        RequestPredicate json = accept(MediaType.APPLICATION_JSON);
        return b.GET("/users/{userId}", json, handler::forUser);
    }

    private void trendingRoutes(RouterFunctions.Builder b) {
        b.GET(trending::top);
    }
}
