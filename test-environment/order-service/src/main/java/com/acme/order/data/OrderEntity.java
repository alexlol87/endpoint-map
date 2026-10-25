package com.acme.order.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue
    private Long id;
    private long customerId;
    private double total;
    private String status = "NEW";

    protected OrderEntity() {
    }

    public OrderEntity(long customerId, double total) {
        this.customerId = customerId;
        this.total = total;
    }

    public Long getId() {
        return id;
    }

    public long getCustomerId() {
        return customerId;
    }

    public double getTotal() {
        return total;
    }

    public String getStatus() {
        return status;
    }
}
