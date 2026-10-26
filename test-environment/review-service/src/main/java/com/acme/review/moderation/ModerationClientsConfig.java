package com.acme.review.moderation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Two WebClient beans: one injected by bean name (no @Qualifier), one by @Qualifier. */
@Configuration
public class ModerationClientsConfig {

    @Bean
    WebClient profileWebClient(WebClient.Builder builder, @Value("${clients.profile-url}") String url) {
        return builder.baseUrl(url).defaultHeader("X-Source", "review-moderation").build();
    }

    @Bean(name = "stockClient")
    WebClient inventoryWebClient(@Value("${clients.inventory-url}") String url) {
        return WebClient.builder().baseUrl(url).build();
    }
}
