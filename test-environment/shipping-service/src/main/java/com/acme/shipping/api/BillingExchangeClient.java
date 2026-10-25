package com.acme.shipping.api;

import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Outbound @HttpExchange client; base URL comes from the RestClient bean in ClientConfig. */
@HttpExchange(url = "/billing")
public interface BillingExchangeClient {

    @PostExchange("/invoices")
    Map<String, Object> createInvoice(@RequestBody Map<String, Object> request);
}
