package com.acme.order.service;

import com.acme.libs.common.TaxRatesApi;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/** RestTemplate bean with a root URI (relative URLs, {sku} template variables) and an @HttpExchange proxy. */
@Service
public class RestockService {

    private final RestTemplate inventory;
    private final TaxRatesApi taxRates;

    public RestockService(@Qualifier("inventoryRestTemplate") RestTemplate inventory, TaxRatesApi taxRates) {
        this.inventory = inventory;
        this.taxRates = taxRates;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> restock(String sku) {
        ResponseEntity<Map> stock = inventory.getForEntity("/inventory/{sku}", Map.class, sku);
        if (stock.getBody() != null && ((Number) stock.getBody().getOrDefault("quantity", 0)).intValue() < 10) {
            inventory.patchForObject("/inventory/{sku}/quantity", Map.of("delta", 100), Void.class, sku);
        }
        Map<String, Object> rate = taxRates.rate("EU");
        return Map.of("sku", sku, "rate", rate.get("rate"));
    }
}
