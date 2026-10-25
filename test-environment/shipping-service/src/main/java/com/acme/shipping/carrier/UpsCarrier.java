package com.acme.shipping.carrier;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Qualifier("ups")
public class UpsCarrier implements CarrierGateway {

    private final RestClient client = RestClient.builder().baseUrl("https://onlinetools.ups.com").build();

    @Override
    public String book(String orderId) {
        return client.post().uri("/api/shipments/v1/ship").body(Map.of("ref", orderId)).retrieve().body(String.class);
    }

    @Override
    public String track(String trackingId) {
        return client.get().uri("/api/track/v1/details/" + trackingId).retrieve().body(String.class);
    }

    @Override
    public void cancel(String trackingId) {
        client.delete().uri("/api/shipments/v1/void/cancel/" + trackingId).retrieve().toBodilessEntity();
    }
}
