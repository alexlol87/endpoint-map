package com.acme.libs.common;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/** RestTemplate wrapper shared by several services. The URL is always a parameter. */
public class InternalRestTemplateClient {

    private final RestTemplate restTemplate;

    public InternalRestTemplateClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public <T> T fetch(String url, Class<T> type) {
        return restTemplate.getForObject(url, type);
    }

    public <T> ResponseEntity<T> fetchEntity(String url, Class<T> type, Object... uriVars) {
        return restTemplate.getForEntity(url, type, uriVars);
    }

    public <T> T send(String url, Object body, Class<T> type) {
        return restTemplate.postForObject(url, body, type);
    }

    public void replace(String url, Object body) {
        restTemplate.put(url, body);
    }

    public void remove(String url) {
        restTemplate.delete(url);
    }

    public <T> ResponseEntity<T> exchange(HttpMethod method, String url, Object body, Class<T> type) {
        return restTemplate.exchange(url, method, new HttpEntity<>(body), type);
    }
}
