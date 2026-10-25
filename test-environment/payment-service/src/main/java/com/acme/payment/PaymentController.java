package com.acme.payment;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("payments")
public class PaymentController {

    private final PaymentGateway gateway;

    public PaymentController(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("charge")
    public String charge(@RequestParam long orderId, @RequestParam double amount) {
        return gateway.charge(orderId, amount);
    }

    @PostMapping("/{id}/refund")
    public void refund(@PathVariable long id) {
        gateway.refund(id);
    }
}
