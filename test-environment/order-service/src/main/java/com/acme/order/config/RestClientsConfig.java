package com.acme.order.config;

import com.acme.libs.common.TaxRatesApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.support.RestTemplateAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.util.DefaultUriBuilderFactory;

/** Two RestTemplate beans with a root URI each, and an @HttpExchange proxy on top of one of them. */
@Configuration
public class RestClientsConfig {

    /** RestTemplateBuilder.rootUri: relative URLs of every call go below it. */
    @Bean
    RestTemplate inventoryRestTemplate(RestTemplateBuilder builder, @Value("${inventory.base-url}") String url) {
        return builder.rootUri(url).build();
    }

    /** DefaultUriBuilderFactory set on a plain RestTemplate. */
    @Bean
    RestTemplate taxRestTemplate(@Value("${tax.base-url}") String url) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory(url));
        return restTemplate;
    }

    /** tax-service implements TaxRatesApi as a controller; here it is a client, via RestTemplateAdapter. */
    @Bean
    TaxRatesApi taxRatesApi(@Qualifier("taxRestTemplate") RestTemplate restTemplate) {
        return HttpServiceProxyFactory.builderFor(RestTemplateAdapter.create(restTemplate)).build()
                .createClient(TaxRatesApi.class);
    }
}
