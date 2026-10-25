package com.acme.search;

import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Builder-style functional routes:
 *   /search                      GET  -> handler.search
 *   /search/products             GET  -> handler.products
 *   /search/products/{sku}       GET  -> handler.productBySku
 *   /search/products/{sku}/similar GET (3 levels of nesting)
 *   /search/products/{sku}/bundle  GET (deep chain: handler -> services -> utils)
 *   /search/suggest              POST -> lambda
 *   /search/admin/reindex        POST (via .add(adminRoutes()))
 *   /search/admin/index          DELETE
 *   /search/history/{userId}     GET (.nest with path predicate)
 */
@Configuration
public class SearchRoutes {

    @Bean
    RouterFunction<ServerResponse> searchRouter(SearchHandler handler) {
        return route()
                .path("/search", b -> b
                        .GET("", handler::search)
                        .path("/products", products -> products
                                .GET("", handler::products)
                                .nest(path("/{sku}"), sku -> sku
                                        .GET("", accept(MediaType.APPLICATION_JSON), handler::productBySku)
                                        .GET("/similar", handler::similar)
                                        .GET("/bundle", handler::bundle)))
                        .POST("/suggest", request -> handler.suggest(request))
                        .nest(path("/history"), h -> h.GET("/{userId}", handler::history)))
                .add(adminRoutes(handler))
                .build();
    }

    /** Router-returning method referenced from the bean above. */
    RouterFunction<ServerResponse> adminRoutes(SearchHandler handler) {
        return RouterFunctions.route()
                .nest(path("/search/admin"), b -> b
                        .POST("/reindex", handler::reindex)
                        .DELETE("/index", handler::dropIndex))
                .build();
    }

    /**
     * Routes generated in a loop from data: the walker cannot know the paths,
     * so the report must list this bean as "not understood" (file + line).
     */
    @Bean
    RouterFunction<ServerResponse> facetRouter(SearchHandler handler) {
        RouterFunctions.Builder builder = route();
        for (String facet : List.of("brand", "color", "size")) {
            builder.GET("/search/facets/" + facet, handler::facet);
        }
        return builder.build();
    }
}
