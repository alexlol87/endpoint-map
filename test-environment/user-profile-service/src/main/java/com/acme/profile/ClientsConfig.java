package com.acme.profile;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ClientsConfig {

    @Bean
    @Qualifier("loyalty")
    WebClient loyaltyWebClient(@Value("${clients.loyalty-url}") String url) {
        return WebClient.builder().baseUrl(url).build();
    }

    @Bean
    @Qualifier("audit")
    WebClient auditWebClient(@Value("${clients.audit-url}") String url) {
        return WebClient.builder().baseUrl(url).defaultHeader("X-Source", "user-profile").build();
    }
}
