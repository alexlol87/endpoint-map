package com.acme.libs.http;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** Feign client with only a service name (no url) -> resolutions entry "feign:pricing-service". */
@FeignClient(name = "pricing-service")
public interface PricingFeignClient {

    @GetMapping("/prices/{sku}")
    Map<String, Object> getPrice(@PathVariable("sku") String sku, @RequestParam("currency") String currency);

    @PostMapping("/prices/quote")
    Map<String, Object> quote(@RequestBody Map<String, Object> request);
}
