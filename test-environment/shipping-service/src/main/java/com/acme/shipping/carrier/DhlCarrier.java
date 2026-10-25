package com.acme.shipping.carrier;

import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Primary
@Component
public class DhlCarrier implements CarrierGateway {

    private final RestClient client = RestClient.create("https://api-eu.dhl.com");

    @Override
    public String book(String orderId) {
        return client.post().uri("/shipping/v2/orders").body(Map.of("ref", orderId)).retrieve().body(String.class);
    }

    @Override
    public String track(String trackingId) {
        return client.get().uri("/track/shipments?trackingNumber={id}", trackingId).retrieve().body(String.class);
    }

    @Override
    public void cancel(String trackingId) {
        client.delete().uri("/shipping/v2/orders/{id}", trackingId).retrieve().toBodilessEntity();
    }
}
