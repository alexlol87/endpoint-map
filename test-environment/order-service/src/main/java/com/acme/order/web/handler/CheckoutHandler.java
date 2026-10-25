package com.acme.order.web.handler;

import com.acme.order.data.OrderEntity;

/** Handler layer behind OrderController: the controller only sees the interface, one impl exists. */
public interface CheckoutHandler {

    OrderEntity handle(long orderId, String region, boolean express);
}
