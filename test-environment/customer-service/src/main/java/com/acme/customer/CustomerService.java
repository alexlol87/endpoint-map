package com.acme.customer;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final StringRedisTemplate redis;
    private final RestClient orderClient;

    public CustomerService(CustomerRepository repository, StringRedisTemplate redis,
                           @Value("${clients.order.url}") String orderUrl) {
        this.repository = repository;
        this.redis = redis;
        this.orderClient = RestClient.builder().baseUrl(orderUrl).build();
    }

    @Cacheable(cacheNames = "customers", key = "#id")
    public CustomerEntity get(long id) {
        return repository.findById(id).orElseThrow();
    }

    @CachePut(value = "customers", key = "#id")
    @CacheEvict(cacheNames = "customer-preferences", key = "#id")
    public CustomerEntity update(long id, CustomerEntity body) {
        return repository.save(body);
    }

    public Map<String, String> preferences(long id) {
        String cached = redis.opsForValue().get("prefs:" + id);
        if (cached == null) {
            if (repository.preferenceCount(id) == 0) {
                return Map.of();
            }
            cached = repository.findPreferencesJson(id);
            redis.opsForValue().set("prefs:" + id, cached, Duration.ofMinutes(10));
        }
        redis.expire("prefs:" + id, Duration.ofHours(1));
        return Map.of("raw", cached);
    }

    /** Calls back into order-service which calls this endpoint again -> cycle. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> ordersSummary(long id) {
        if (redis.hasKey("summary-lock:" + id)) {
            return Map.of();
        }
        return orderClient.get()
                .uri("/orders/by-customer/{customerId}/summary", id)
                .retrieve()
                .body(Map.class);
    }
}
