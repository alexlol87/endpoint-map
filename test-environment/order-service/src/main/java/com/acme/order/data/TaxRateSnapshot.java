package com.acme.order.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/** No @Table, no @Entity name -> Spring Boot's default naming gives table tax_rate_snapshot. */
@Entity
public class TaxRateSnapshot {

    @Id
    @GeneratedValue
    private Long id;
    private String region;
    private double rate;
    private String createdBy = "checkout";

    protected TaxRateSnapshot() {
    }

    public TaxRateSnapshot(String region, double rate) {
        this.region = region;
        this.rate = rate;
    }
}
