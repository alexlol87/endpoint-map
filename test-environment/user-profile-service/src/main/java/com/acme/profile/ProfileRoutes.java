package com.acme.profile;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Functional routes next to ProfileController; spring.webflux.base-path=/profile-api applies here too:
 *   GET /profile-api/profiles/{userId}/summary  (path from constants, Supplier-style .path, block lambda)
 *   GET /profile-api/profiles/ping              (static route inside the Supplier)
 */
@Configuration
public class ProfileRoutes {

    static final String PROFILES = "/profiles";
    static final String SUMMARY = "/{userId}" + "/summary";

    @Bean
    RouterFunction<ServerResponse> profileRouter(ProfileService service) {
        return RouterFunctions.route()
                .path(PROFILES, () -> route(GET(SUMMARY), request -> {
                    String userId = request.pathVariable("userId");
                    return service.get(userId).flatMap(p -> ServerResponse.ok().bodyValue(p));
                }).andRoute(GET("/ping"), request -> ServerResponse.ok().bodyValue(Map.of("ok", true))))
                .build();
    }
}
