package com.acme.order.client;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Service-local Feign client with an environment-variable url. */
@FeignClient(name = "shipping", url = "${SHIPPING_SERVICE_URL}")
public interface ShippingFeignClient {

    @PostMapping("/shipments/express")
    Map<String, Object> createExpress(@RequestBody Map<String, Object> request);

    @PostMapping("/shipments")
    Map<String, Object> createStandard(@RequestBody Map<String, Object> request);
}
