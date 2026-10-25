package com.acme.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Two WebClient beans of the same type, told apart by bean name / @Qualifier. */
@Configuration
public class WebClientConfig {

    @Bean
    WebClient pricingWebClient(WebClient.Builder builder, @Value("${clients.pricing.base-url}") String url) {
        return builder.baseUrl(url).build();
    }

    @Bean("inventoryWebClient")
    WebClient inventoryWebClient(@Value("${clients.inventory.base-url}") String url) {
        return WebClient.builder().baseUrl(url).build();
    }
}
