package com.acme.order.util;

import com.acme.order.data.OrderEntity;
import java.util.Map;

public final class ShippingPayloadUtils {

    private ShippingPayloadUtils() {
    }

    public static Map<String, Object> payload(OrderEntity order, boolean express) {
        return Map.of("orderId", order.getId(), "priority", express ? "HIGH" : "NORMAL");
    }
}
