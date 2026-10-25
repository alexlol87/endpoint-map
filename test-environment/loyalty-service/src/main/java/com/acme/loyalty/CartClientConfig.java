package com.acme.loyalty;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class CartClientConfig {

    @Bean
    WebClient cartWebClient(@Value("${cart.url:http://localhost:9082}") String url) {
        return WebClient.builder().baseUrl(url).build();
    }

    @Bean
    HttpServiceProxyFactory cartProxyFactory(WebClient cartWebClient) {
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(cartWebClient)).build();
    }

    @Bean
    CartExchange cartExchange(HttpServiceProxyFactory cartProxyFactory) {
        return cartProxyFactory.createClient(CartExchange.class);
    }
}
