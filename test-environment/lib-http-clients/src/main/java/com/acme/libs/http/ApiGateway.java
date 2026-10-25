package com.acme.libs.http;

import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/**
 * Generic RestClient wrapper: base URL, path and verb are all method parameters,
 * so the real target is only known from the caller's arguments.
 */
public class ApiGateway {

    private final RestClient restClient;

    public ApiGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    public ApiGateway() {
        this(RestClient.create());
    }

    public <T> T get(String baseUrl, String path, Class<T> type) {
        return restClient.get().uri(baseUrl + path).retrieve().body(type);
    }

    public <T> T post(String baseUrl, String path, Object body, Class<T> type) {
        return restClient.post().uri(baseUrl + path).body(body).retrieve().body(type);
    }

    public void delete(String baseUrl, String path) {
        restClient.delete().uri(baseUrl + path).retrieve().toBodilessEntity();
    }

    /** Verb passed in by the caller. */
    public <T> T call(HttpMethod method, String url, Class<T> type) {
        return restClient.method(method).uri(url).retrieve().body(type);
    }
}
