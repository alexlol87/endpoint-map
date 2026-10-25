package com.acme.loyalty;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class LoyaltyRoutes {

    @Bean
    RouterFunction<ServerResponse> loyaltyRouter(LoyaltyService service) {
        return route(POST("/loyalty/{userId}/simulate"),
                request -> ServerResponse.ok().body(service.simulate(request.pathVariable("userId")), java.util.Map.class));
    }
}
