package com.acme.order.service;

import com.acme.order.util.TaxRestUtils;
import org.springframework.stereotype.Service;

@Service
public class OrderTaxService {

    private final TaxRestUtils taxRestUtils;

    public OrderTaxService(TaxRestUtils taxRestUtils) {
        this.taxRestUtils = taxRestUtils;
    }

    public double rateFor(String region) {
        return "XX".equals(region) ? 0 : taxRestUtils.fetchRate(region);
    }
}
