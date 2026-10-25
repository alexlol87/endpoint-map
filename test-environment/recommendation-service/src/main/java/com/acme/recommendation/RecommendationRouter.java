package com.acme.recommendation;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RequestPredicates.method;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;
import static org.springframework.web.reactive.function.server.RouterFunctions.nest;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Static-style functional routes:
 *   GET    /recs/{userId}                     method ref
 *   POST   /recs/feedback                     andRoute
 *   GET    /recs/popular                      path(..).and(method(GET))
 *   DELETE /recs/admin/cache                  nest(path, route(...))
 *   PUT    /recs/admin/models/{model}         nested 2 levels + lambda handler
 *   GET    /recs/v2/users/{userId}/bundle     .and(otherRouter())
 */
@Configuration
public class RecommendationRouter {

    @Bean
    public RouterFunction<ServerResponse> recommendationRoutes(RecommendationHandler handler) {
        return RouterFunctions
                .route(GET("/recs/{userId}").and(accept(MediaType.APPLICATION_JSON)), handler::forUser)
                .andRoute(POST("/recs/feedback"), handler::feedback)
                .andRoute(path("/recs/popular").and(method(HttpMethod.GET)), handler::popular)
                .andNest(path("/recs/admin"),
                        route(DELETE("/cache"), handler::clearCache)
                                .and(nest(path("/models"),
                                        route(PUT("/{model}"), request -> handler.retrain(request.pathVariable("model"))))))
                .and(v2Routes(handler));
    }

    private RouterFunction<ServerResponse> v2Routes(RecommendationHandler handler) {
        return nest(path("/recs/v2"),
                route(GET("/users/{userId}/bundle"), handler::bundle));
    }
}
