package com.acme.tax;

import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TaxService {

    private static final String RATES_KEY = "tax:rates";

    private final RedisTemplate<String, Object> redisTemplate;

    public TaxService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Map<Object, Object> allRates() {
        return redisTemplate.opsForHash().entries(RATES_KEY);
    }

    @Cacheable("tax-rates")
    public double rate(String region) {
        Object v = redisTemplate.opsForHash().get(RATES_KEY, region);
        return v == null ? 0.0 : ((Number) v).doubleValue();
    }

    public double calculate(Map<String, Object> body) {
        double sum = 0;
        for (Object line : (Iterable<?>) body.get("lines")) {
            sum += rate(String.valueOf(((Map<?, ?>) line).get("region")));
        }
        redisTemplate.opsForList().rightPush("tax:audit", sum);
        redisTemplate.opsForZSet().add("tax:leaderboard", String.valueOf(body.get("customer")), sum);
        return sum;
    }
}
