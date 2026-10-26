package com.acme.libs.common;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Shared HTTP interface: tax-service's TaxController implements it (inbound endpoint), order-service builds a client
 * proxy of it (outbound call).
 */
@HttpExchange("/tax")
public interface TaxRatesApi {

    @GetExchange("/rates/{region}")
    Map<String, Object> rate(@PathVariable("region") String region);
}
