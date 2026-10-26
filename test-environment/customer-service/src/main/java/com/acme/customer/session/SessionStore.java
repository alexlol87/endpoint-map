package com.acme.customer.session;

import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis through an operations variable, keys built by the SessionKeys util. */
@Component
public class SessionStore {

    private final StringRedisTemplate redis;

    public SessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Map<String, String> read(long customerId) {
        HashOperations<String, String, String> hash = redis.opsForHash();
        Map<String, String> session = hash.entries(SessionKeys.session(customerId));
        redis.expire(SessionKeys.session(customerId), Duration.ofMinutes(30));
        return session;
    }

    public void remove(long customerId, String token) {
        // a guest (negative id) and a customer have different keys: both are possible
        redis.delete(customerId < 0 ? SessionKeys.GUEST + customerId : SessionKeys.session(customerId));
        redis.opsForSet().remove(SessionKeys.ACTIVE, String.valueOf(customerId));
        redis.delete(token); // the token is the whole key: nothing known from code
    }
}
