package com.acme.warehouse;

import com.acme.libs.reactive.ReactiveHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WarehouseHttpConfig {

    @Bean
    ReactiveHttp auditHttp(@Value("${warehouse.audit-url:http://localhost:9087}") String auditUrl) {
        return new ReactiveHttp(WebClient.builder().baseUrl(auditUrl).build());
    }
}
