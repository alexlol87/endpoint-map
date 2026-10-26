package com.acme.notification;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * url from application-prod.yml is an internal DNS name (loyalty-service.prod.acme.io) that looks public: the matcher
 * calls it external, discover asks about it as host:loyalty-service.prod.acme.io, resolutions.yml can say service:.
 */
@FeignClient(name = "loyalty", url = "${loyalty.base-url}")
public interface LoyaltyClient {

    @GetMapping("/loyalty/{userId}/tier")
    Map<String, Object> getTier(@PathVariable("userId") String userId);
}
