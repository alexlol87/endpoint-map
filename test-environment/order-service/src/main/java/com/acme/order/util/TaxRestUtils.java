package com.acme.order.util;

import com.acme.libs.http.HttpPaths;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** A "utils" class that does I/O: last hop of the checkout chain -> tax-service GET /tax-api/tax/rates/{region}. */
@Component
public class TaxRestUtils {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${tax.base-url}")
    private String taxBaseUrl;

    public double fetchRate(String region) {
        Map<?, ?> body = restTemplate.getForObject(UrlUtils.join(taxBaseUrl, HttpPaths.TAX_RATE_FOR_REGION), Map.class, region);
        return ((Number) body.get("rate")).doubleValue();
    }
}
