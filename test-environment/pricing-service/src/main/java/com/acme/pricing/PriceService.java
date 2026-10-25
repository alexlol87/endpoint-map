package com.acme.pricing;

import com.acme.libs.http.HttpPaths;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class PriceService {

    private final RestClient taxClient;
    private final RestClient fxClient;

    public PriceService(@Value("${tax.url}") String taxUrl,
                        @Value("${fx.url:https://api.exchangerate.host}") String fxUrl) {
        this.taxClient = RestClient.builder().baseUrl(taxUrl).build();
        this.fxClient = RestClient.builder().baseUrl(fxUrl).build();
    }

    public double price(String sku, String currency) {
        double base = sku.startsWith("PREMIUM") ? 100.0 : 10.0;
        String region;
        switch (currency) {
            case "EUR":
                region = "eu";
                break;
            case "GBP":
                region = "uk";
                break;
            default:
                region = "us";
        }
        double taxed = base * (1 + taxRate(region));
        return "EUR".equals(currency) ? taxed : convert(taxed, currency);
    }

    @CircuitBreaker(name = "tax", fallbackMethod = "defaultTaxRate")
    @Retry(name = "tax")
    double taxRate(String region) {
        Map<?, ?> rate = taxClient.get().uri(HttpPaths.TAX_RATE_FOR_REGION, region).retrieve().body(Map.class);
        return ((Number) rate.get("rate")).doubleValue();
    }

    /** Fallback itself makes a different HTTP call -> traced too. */
    double defaultTaxRate(String region, Throwable t) {
        Map<?, ?> all = taxClient.get().uri(HttpPaths.TAX_RATES).retrieve().body(Map.class);
        return all.containsKey(region) ? ((Number) all.get(region)).doubleValue() : 0.2;
    }

    /** External API (not one of our services). */
    double convert(double amount, String currency) {
        Map<?, ?> fx = fxClient.get()
                .uri(uri -> uri.path("/convert").queryParam("from", "EUR").queryParam("to", currency).queryParam("amount", amount).build())
                .retrieve().body(Map.class);
        return ((Number) fx.get("result")).doubleValue();
    }

    public double quote(Map<String, Object> body) {
        double total = ((Number) body.get("total")).doubleValue();
        Object coupon = body.get("coupon");
        double discount = switch (String.valueOf(coupon)) {
            case "TEN" -> 0.10;
            case "VIP" -> {
                double r = taxRate("vip");
                yield 0.25 - r;
            }
            default -> 0;
        };
        return total * (1 - discount);
    }
}
