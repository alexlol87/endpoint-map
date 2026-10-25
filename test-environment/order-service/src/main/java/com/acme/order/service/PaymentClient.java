package com.acme.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** RestTemplate with @Value base URL (default given) and environment placeholder. */
@Component
public class PaymentClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${payment.base-url:http://localhost:8085}")
    private String paymentBaseUrl;

    public void charge(long orderId, double amount) {
        restTemplate.postForEntity(paymentBaseUrl + "/payments/charge?orderId={id}&amount={amount}", null, Void.class, orderId, amount);
    }

    public void refund(long orderId) {
        restTemplate.postForObject(paymentBaseUrl + "/payments/" + orderId + "/refund", null, Void.class);
    }
}
