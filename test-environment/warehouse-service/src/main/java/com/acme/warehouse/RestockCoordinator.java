package com.acme.warehouse;

import com.acme.libs.reactive.ReactiveHttp;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Library wrapper objects built three ways: in the constructor, as a @Bean, and inline with new. */
@Service
public class RestockCoordinator {

    private final ReactiveHttp inventory;   // new ReactiveHttp(WebClient.create(url)) right here
    private final ReactiveHttp audit;       // the @Bean from WarehouseHttpConfig

    public RestockCoordinator(@Value("${warehouse.stock-url:http://localhost:8083}") String stockUrl, ReactiveHttp audit) {
        this.inventory = new ReactiveHttp(WebClient.create(stockUrl));
        this.audit = audit;
    }

    public Mono<Void> restock(String sku) {
        return inventory.get("/inventory/" + sku, Map.class)
                // verb passed to the wrapper
                .then(inventory.send(HttpMethod.PATCH, "/inventory/" + sku + "/quantity"))
                .then(audit.post("/audit/events", Map.of("type", "restock", "sku", sku), Void.class))
                // built inline through the this(..) convenience constructor
                .then(new ReactiveHttp("http://localhost:8087").post("/billing/invoices", Map.of("sku", sku), Map.class))
                .then();
    }
}
