package com.acme.customer;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** No @Table -> table name comes from @Entity(name). */
@Entity(name = "customer_accounts")
public class CustomerEntity {
    @Id
    private Long id;
    private String name;
    private boolean blocked;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isBlocked() {
        return blocked;
    }
}
