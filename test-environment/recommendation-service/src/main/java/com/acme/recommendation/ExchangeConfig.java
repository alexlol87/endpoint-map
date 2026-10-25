package com.acme.recommendation;

import com.acme.libs.reactive.ExchangeClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class ExchangeConfig {

    /** Proxy built inline with WebClientAdapter; base URL includes user-profile-service's base path. */
    @Bean
    ProfileExchange profileExchange(@Value("${clients.profile}") String url) {
        WebClient client = WebClient.builder().baseUrl(url).build();
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client)).build().createClient(ProfileExchange.class);
    }

    /** Proxy built through the library factory. */
    @Bean
    CatalogExchange catalogExchange(@Value("${clients.catalog}") String url) {
        return ExchangeClients.create(url, CatalogExchange.class);
    }
}
