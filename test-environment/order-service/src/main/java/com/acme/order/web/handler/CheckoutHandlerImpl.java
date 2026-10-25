package com.acme.order.web.handler;

import com.acme.order.data.OrderEntity;
import com.acme.order.service.CheckoutService;
import org.springframework.stereotype.Component;

@Component
public class CheckoutHandlerImpl implements CheckoutHandler {

    private final CheckoutService checkoutService;

    public CheckoutHandlerImpl(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Override
    public OrderEntity handle(long orderId, String region, boolean express) {
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region required");
        }
        return checkoutService.checkout(orderId, region.toUpperCase(), express);
    }
}
