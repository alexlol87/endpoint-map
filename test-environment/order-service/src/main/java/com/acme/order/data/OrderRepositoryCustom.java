package com.acme.order.data;

/** Spring Data custom fragment: implemented by hand in {@link OrderRepositoryImpl}. */
public interface OrderRepositoryCustom {

    int purgeLines(long orderId);
}
