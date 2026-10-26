package com.acme.payment;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** External Stripe calls with an env placeholder host, @Retryable + @Recover. */
@Component
public class PaymentGateway {

    private final RestTemplate restTemplate;

    @Value("${PAYMENT_HOST}")
    private String stripeHost;

    @Value("${fraud.url}")
    private String fraudUrl;

    public PaymentGateway(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 200))
    public String charge(long orderId, double amount) {
        Boolean risky = restTemplate.getForObject(fraudUrl + "/v1/score?order=" + orderId, Boolean.class);
        if (Boolean.TRUE.equals(risky)) {
            throw new IllegalStateException("fraud");
        }
        Map<?, ?> res = restTemplate.exchange(stripeHost + "/v1/charges", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", amount)), Map.class).getBody();
        return String.valueOf(res.get("id"));
    }

    @Recover
    public String recoverCharge(RestClientException e, long orderId, double amount) {
        restTemplate.put(stripeHost + "/v1/payment_intents/" + orderId + "/cancel", null);
        return "failed";
    }

    public void refund(long orderId) {
        // GET /v1/charges/{id} is not in the trimmed docs/external/openapi-stripe.json: reported as not in its OpenAPI
        restTemplate.getForObject(stripeHost + "/v1/charges/{id}", Map.class, orderId);
        restTemplate.execute(stripeHost + "/v1/refunds?charge={id}", HttpMethod.POST, null, null, orderId);
    }
}
