package com.acme.shipping;

import com.acme.shipping.api.BillingExchangeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class ClientConfig {

    @Bean
    RestClient billingRestClient(@Value("${acme.clients.billing-url}") String billingUrl) {
        return RestClient.builder().baseUrl(billingUrl).build();
    }

    @Bean
    BillingExchangeClient billingExchangeClient(RestClient billingRestClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(billingRestClient))
                .build()
                .createClient(BillingExchangeClient.class);
    }
}
