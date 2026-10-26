package com.acme.inventory.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "stock_audit")
public class StockAudit {

    @Id
    @GeneratedValue
    private Long id;
    private String sku;
    private int quantity;
    private Instant createdAt = Instant.now();

    protected StockAudit() {
    }

    public StockAudit(String sku, int quantity) {
        this.sku = sku;
        this.quantity = quantity;
    }

    public String getSku() {
        return sku;
    }
}
