package com.acme.order.service;

import com.acme.order.client.ShippingFeignClient;
import com.acme.order.data.OrderEntity;
import com.acme.order.util.ShippingPayloadUtils;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class FulfilmentService {

    private final PaymentClient paymentClient;
    private final ShippingFeignClient shipping;

    public FulfilmentService(PaymentClient paymentClient, ShippingFeignClient shipping) {
        this.paymentClient = paymentClient;
        this.shipping = shipping;
    }

    public void payAndShip(OrderEntity order, double total, boolean express) {
        paymentClient.charge(order.getId(), total);
        Map<String, Object> payload = ShippingPayloadUtils.payload(order, express);
        if (express) {
            shipping.createExpress(payload);
        } else {
            shipping.createStandard(payload);
        }
    }
}
