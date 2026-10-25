package com.acme.libs.http;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Feign client with a literal url, including the target's servlet context path. */
@FeignClient(name = "customers", url = "http://localhost:8082/customers-api")
public interface CustomerFeignClient {

    @GetMapping("/customers/{id}")
    Map<String, Object> getCustomer(@PathVariable("id") long id);

    @GetMapping("/customers/{id}/orders-summary")
    Map<String, Object> getOrdersSummary(@PathVariable("id") long id);
}
