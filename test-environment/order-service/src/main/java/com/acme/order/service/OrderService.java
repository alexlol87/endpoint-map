package com.acme.order.service;

import com.acme.libs.http.CustomerFeignClient;
import com.acme.libs.http.InventoryFeignClient;
import com.acme.libs.http.PricingFeignClient;
import com.acme.order.client.ShippingFeignClient;
import com.acme.order.data.OrderEntity;
import com.acme.order.data.OrderRepository;
import com.acme.order.web.OrderController.CreateOrderRequest;
import com.acme.order.web.OrderController.OrderSummary;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final InventoryFeignClient inventory;
    private final PricingFeignClient pricing;
    private final CustomerFeignClient customers;
    private final ShippingFeignClient shipping;
    private final PaymentClient paymentClient;

    public OrderService(OrderRepository orders, InventoryFeignClient inventory, PricingFeignClient pricing,
                        CustomerFeignClient customers, ShippingFeignClient shipping, PaymentClient paymentClient) {
        this.orders = orders;
        this.inventory = inventory;
        this.pricing = pricing;
        this.customers = customers;
        this.shipping = shipping;
        this.paymentClient = paymentClient;
    }

    public OrderEntity get(long id) {
        return orders.findById(id).orElseThrow();
    }

    public List<OrderEntity> list(Long customerId) {
        return customerId == null ? orders.findAll() : orders.findByCustomerId(customerId);
    }

    @Transactional
    public OrderEntity create(CreateOrderRequest request) {
        Map<String, Object> customer = customers.getCustomer(request.customerId());
        if (Boolean.TRUE.equals(customer.get("blocked"))) {
            throw new IllegalStateException("customer blocked");
        }
        double total = 0;
        for (String sku : request.skus()) {
            Map<String, Object> stock = inventory.getStock(sku);
            if (((Number) stock.get("available")).intValue() > 0) {
                inventory.reserve(sku);
                total += priceOf(sku);
            } else {
                inventory.adjust(sku);
            }
        }
        if (request.couponCode() != null && !request.couponCode().isBlank()) {
            total = applyCoupon(total, request.couponCode());
        }
        OrderEntity saved = orders.save(new OrderEntity(request.customerId(), total));
        try {
            paymentClient.charge(saved.getId(), total);
        } catch (FeignException e) {
            request.skus().forEach(inventory::release);
            throw e;
        } finally {
            orders.flush();
        }
        if (request.express()) {
            shipping.createExpress(Map.of("orderId", saved.getId()));
        } else {
            shipping.createStandard(Map.of("orderId", saved.getId()));
        }
        return saved;
    }

    @CircuitBreaker(name = "pricing", fallbackMethod = "priceFallback")
    double priceOf(String sku) {
        return ((Number) pricing.getPrice(sku, "EUR").get("amount")).doubleValue();
    }

    double priceFallback(String sku, Throwable t) {
        return orders.findLastPriceForSku(sku);
    }

    private double applyCoupon(double total, String coupon) {
        Map<String, Object> quote = pricing.quote(Map.of("total", total, "coupon", coupon));
        return ((Number) quote.get("total")).doubleValue();
    }

    @Transactional
    public void cancel(long id) {
        OrderEntity order = get(id);
        switch (order.getStatus()) {
            case "NEW" -> orders.updateStatus(id, "CANCELLED");
            case "PAID" -> {
                paymentClient.refund(id);
                orders.updateStatus(id, "REFUNDED");
            }
            default -> throw new IllegalStateException("cannot cancel " + order.getStatus());
        }
    }

    public void delete(long id) {
        orders.deleteById(id);
    }

    public OrderSummary summaryForCustomer(long customerId) {
        // customer-service GET /customers/{id}/orders-summary calls us back here -> cycle
        Map<String, Object> summary = customers.getOrdersSummary(customerId);
        return new OrderSummary(customerId, orders.countByCustomerId(customerId) + summary.size());
    }
}
