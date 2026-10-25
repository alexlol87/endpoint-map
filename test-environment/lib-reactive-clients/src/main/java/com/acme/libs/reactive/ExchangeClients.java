package com.acme.libs.reactive;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Factory used by services to build HTTP interface proxies on top of a WebClient. */
public final class ExchangeClients {

    private ExchangeClients() {
    }

    public static <T> T create(String baseUrl, Class<T> type) {
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build().createClient(type);
    }
}
