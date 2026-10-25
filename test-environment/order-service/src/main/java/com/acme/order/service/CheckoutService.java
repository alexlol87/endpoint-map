package com.acme.order.service;

import com.acme.libs.http.CustomerChecks;
import com.acme.libs.http.CustomerFeignClient;
import com.acme.order.data.OrderEntity;
import com.acme.order.data.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deep chain: controller -> handler -> CheckoutService -> other services -> utils. */
@Service
public class CheckoutService {

    private final OrderRepository orders;
    private final OrderPricingService pricingService;
    private final FulfilmentService fulfilmentService;
    private final CustomerFeignClient customers;

    public CheckoutService(OrderRepository orders, OrderPricingService pricingService, FulfilmentService fulfilmentService,
                           CustomerFeignClient customers) {
        this.orders = orders;
        this.pricingService = pricingService;
        this.fulfilmentService = fulfilmentService;
        this.customers = customers;
    }

    @Transactional
    public OrderEntity checkout(long orderId, String region, boolean express) {
        OrderEntity order = orders.findById(orderId).orElseThrow();
        CustomerChecks.requireActive(customers, order.getCustomerId());
        double total = pricingService.finalTotal(order, region);
        fulfilmentService.payAndShip(order, total, express);
        orders.updateStatus(orderId, "PAID");
        return order;
    }
}
