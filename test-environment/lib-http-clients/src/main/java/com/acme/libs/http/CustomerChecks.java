package com.acme.libs.http;

import java.util.Map;

/** Static library helper that takes the Feign client as an argument: two static hops before the Feign call. */
public final class CustomerChecks {

    private CustomerChecks() {
    }

    public static void requireActive(CustomerFeignClient customers, long customerId) {
        if (Boolean.TRUE.equals(load(customers, customerId).get("blocked"))) {
            throw new IllegalStateException("customer blocked");
        }
    }

    private static Map<String, Object> load(CustomerFeignClient customers, long customerId) {
        return customers.getCustomer(customerId);
    }
}
