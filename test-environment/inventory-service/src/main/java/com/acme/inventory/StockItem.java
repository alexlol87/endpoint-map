package com.acme.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** No @Table and no @Entity name -> table name = class name. */
@Entity
public class StockItem {
    @Id
    private String sku;
    private int quantity;
    private String warehouse;

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getWarehouse() {
        return warehouse;
    }
}
