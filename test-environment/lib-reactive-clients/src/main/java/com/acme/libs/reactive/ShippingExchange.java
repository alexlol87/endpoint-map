package com.acme.libs.reactive;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;

/** Spring HTTP interface client, shared through the library. */
@HttpExchange("/shipments")
public interface ShippingExchange {

    @GetExchange("/{id}")
    Mono<Map<String, Object>> getShipment(@PathVariable("id") String id);

    @PostExchange
    Mono<Map<String, Object>> createShipment(@RequestBody Map<String, Object> request);

    @DeleteExchange("/{id}")
    Mono<Void> cancelShipment(@PathVariable("id") String id);
}
