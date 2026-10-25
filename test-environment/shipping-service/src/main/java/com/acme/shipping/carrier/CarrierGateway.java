package com.acme.shipping.carrier;

public interface CarrierGateway {
    String book(String orderId);

    String track(String trackingId);

    void cancel(String trackingId);
}
