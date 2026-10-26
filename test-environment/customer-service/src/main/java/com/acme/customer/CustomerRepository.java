package com.acme.customer;

import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

public interface CustomerRepository extends CrudRepository<CustomerEntity, Long> {

    @Query(value = "select p.json from customer_preferences p join customer_accounts c on c.id = p.customer_id where c.id = ?1", nativeQuery = true)
    String findPreferencesJson(long customerId);

    /** Spring Data's @NativeQuery; a comma join, upper case and the default schema name the same two tables. */
    @NativeQuery("SELECT COUNT(*) FROM CUSTOMER_ACCOUNTS c, public.customer_preferences p WHERE p.customer_id = c.id AND c.id = ?1")
    long preferenceCount(long customerId);
}
