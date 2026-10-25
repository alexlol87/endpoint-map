package com.acme.shipping.api;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Inbound endpoints declared with @HttpExchange and implemented by a controller. */
@HttpExchange("/shipments")
public interface ShipmentApi {

    @PostExchange
    Map<String, Object> create(@RequestBody Map<String, Object> request);

    @PostExchange("/express")
    Map<String, Object> createExpress(@RequestBody Map<String, Object> request);

    @GetExchange("/{id}")
    Map<String, Object> get(@PathVariable("id") String id);

    @DeleteExchange("/{id}")
    void cancel(@PathVariable("id") String id);
}
