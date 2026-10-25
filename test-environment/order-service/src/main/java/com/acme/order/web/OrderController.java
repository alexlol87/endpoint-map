package com.acme.order.web;

import com.acme.order.data.OrderEntity;
import com.acme.order.service.OrderService;
import com.acme.order.web.handler.CheckoutHandler;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Annotated endpoints: class prefix, multiple paths per method, path vars. Context path /api applies. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final CheckoutHandler checkoutHandler;

    public OrderController(OrderService orderService, CheckoutHandler checkoutHandler) {
        this.orderService = orderService;
        this.checkoutHandler = checkoutHandler;
    }

    @GetMapping("/{id}")
    public OrderEntity get(@PathVariable long id) {
        return orderService.get(id);
    }

    @GetMapping({"", "/search"})
    public List<OrderEntity> list(@RequestParam(required = false) Long customerId) {
        return orderService.list(customerId);
    }

    @PostMapping
    public OrderEntity create(@RequestBody CreateOrderRequest request) {
        return orderService.create(request);
    }

    /** In code but missing from openapi.json -> report "code-only". */
    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable long id) {
        orderService.cancel(id);
    }

    /** Deep chain: handler -> CheckoutService -> pricing/tax/fulfilment services -> utils (tax call is 5 hops down). */
    @PostMapping("/{id}/checkout")
    public OrderEntity checkout(@PathVariable long id, @RequestParam String region,
                                @RequestParam(defaultValue = "false") boolean express) {
        return checkoutHandler.handle(id, region, express);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        orderService.delete(id);
    }

    /** Called back by customer-service -> cross-service cycle customer -> order -> customer. */
    @GetMapping("/by-customer/{customerId}/summary")
    public OrderSummary summaryForCustomer(@PathVariable long customerId) {
        return orderService.summaryForCustomer(customerId);
    }

    public record CreateOrderRequest(long customerId, List<String> skus, boolean express, String couponCode) {
    }

    public record OrderSummary(long customerId, int count) {
    }
}
