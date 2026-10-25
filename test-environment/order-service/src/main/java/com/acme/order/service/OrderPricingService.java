package com.acme.order.service;

import com.acme.order.data.OrderEntity;
import com.acme.order.util.MoneyUtils;
import org.springframework.stereotype.Service;

@Service
public class OrderPricingService {

    private final OrderTaxService taxService;

    public OrderPricingService(OrderTaxService taxService) {
        this.taxService = taxService;
    }

    public double finalTotal(OrderEntity order, String region) {
        double rate = taxService.rateFor(region);
        return MoneyUtils.round(MoneyUtils.withTax(order.getTotal(), rate));
    }
}
