package com.acme.customer;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

public interface CustomerRepository extends CrudRepository<CustomerEntity, Long> {

    @Query(value = "select p.json from customer_preferences p join customer_accounts c on c.id = p.customer_id where c.id = ?1", nativeQuery = true)
    String findPreferencesJson(long customerId);
}
