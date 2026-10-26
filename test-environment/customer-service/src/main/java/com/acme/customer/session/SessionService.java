package com.acme.customer.session;

import java.util.Map;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/** Cache names from @CacheConfig and inside @Caching; the store below it is still traced. */
@Service
@CacheConfig(cacheNames = "sessions")
public class SessionService {

    private final SessionStore store;

    public SessionService(SessionStore store) {
        this.store = store;
    }

    @Cacheable(key = "#customerId")
    public Map<String, String> current(long customerId) {
        return store.read(customerId);
    }

    @Caching(evict = {@CacheEvict(key = "#customerId"), @CacheEvict(cacheNames = SessionKeys.TOKENS_CACHE, allEntries = true)})
    public void logout(long customerId, String token) {
        if (current(customerId).isEmpty()) { // self-invocation: no proxy, so no cache, but the store is read
            return;
        }
        store.remove(customerId, token);
    }
}
