package com.endpointmap;

import static com.endpointmap.RepositoryTablesTest.byId;
import static com.endpointmap.RepositoryTablesTest.list;
import static com.endpointmap.RepositoryTablesTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: Redis template operations and cache annotations as redis nodes and edges (issue #12). */
class RedisCacheTest {

    @TempDir
    static Path dir;
    static Map<String, JsonNode> nodes;
    static List<JsonNode> edges;

    @BeforeAll
    static void map() throws Exception {
        String res = dir.resolve("resolutions.yml").toString();
        run("discover", RepositoryTablesTest.ENV, "--resolutions", res);
        run("map", RepositoryTablesTest.ENV, "--resolutions", res, "--out", dir.resolve("out").toString());
        JsonNode graph = new ObjectMapper().readTree(dir.resolve("out/graph.json").toFile());
        nodes = byId(graph);
        edges = list(graph.get("edges"));
    }

    /** "to op [low]" of every redis edge from {@code from}. */
    static List<String> redis(String from) {
        return edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("kind").asText().equals("redis"))
                .peek(e -> assertEquals("redis", e.get("client").asText()))
                .map(e -> e.get("to").asText() + " " + e.get("op").asText() + (e.has("confidence") ? " low" : "")).sorted().toList();
    }

    @Test
    void stringRedisTemplateValueOpsExpireHasKeyAndPrefixPatterns() {
        assertEquals(List.of("redis:prefs:* expire", "redis:prefs:* value.get", "redis:prefs:* value.set"),
                redis("customer-service GET /customers-api/customers/{id}/preferences"));
        assertEquals(List.of("redis:summary-lock:* hasKey"), redis("customer-service GET /customers-api/customers/{id}/orders-summary"));
        assertEquals("prefs:*", nodes.get("redis:prefs:*").get("keyPattern").asText());
        assertEquals("redis", nodes.get("redis:prefs:*").get("kind").asText());
    }

    @Test
    void redisTemplateHashListZSetWithConstantKeys() {
        assertEquals(List.of("redis:tax-rates::* cache.get", "redis:tax:audit list.rightPush", "redis:tax:leaderboard zset.add",
                "redis:tax:rates hash.get"), redis("tax-service POST /tax-api/tax/calculate"));
    }

    @Test
    void reactiveTemplates() {
        assertEquals(List.of("redis:cart:* expire", "redis:cart:* hash.put"), redis("cart-service POST /carts/{userId}/items"));
        assertEquals(List.of("redis:cart:* delete"), redis("cart-service DELETE /carts/{userId}"));
        assertEquals(List.of("redis:events:count value.increment", "redis:searches:* list.leftPush"),
                redis("analytics-service POST /analytics/events"));
        assertEquals(List.of("redis:trending zset.reverseRange"), redis("analytics-service GET /analytics/trending"));
        assertEquals(List.of("redis:hot-skus set.members", "redis:hot-stock::* cache.get"), redis("warehouse-service GET /warehouse/stock/hot"));
    }

    @Test
    void cacheAnnotationsGiveTheCacheName() {
        assertEquals(List.of("redis:customers::* cache.get"), redis("customer-service GET /customers-api/customers/{id}"));
        assertEquals(List.of("redis:customer-preferences::* cache.evict", "redis:customers::* cache.put"),
                redis("customer-service PUT /customers-api/customers/{id}"));
        assertEquals("customers", nodes.get("redis:customers::*").get("cache").asText());
    }

    @Test
    void deepChainWithCacheConfigCachingOperationsVariableKeyUtilsAndADynamicKey() {
        // @CacheConfig default name, the cached method still traced down to the store; key built by a static util
        assertEquals(List.of("redis:session:* expire", "redis:session:* hash.entries", "redis:sessions::* cache.get"),
                redis("customer-service GET /customers-api/customers/{id}/session"));
        // @Caching with two evicts, a constant key, and a key nothing in code tells: redis:*
        String from = "customer-service DELETE /customers-api/customers/{id}/session";
        assertEquals(List.of("redis:* delete", "redis:session-tokens::* cache.evict", "redis:session:* delete",
                "redis:session:active set.remove", "redis:sessions::* cache.evict"), redis(from));
        JsonNode store = edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals("redis:session:active"))
                .findFirst().orElseThrow();
        assertEquals(List.of("SessionHandler.end", "SessionService.logout", "SessionStore.remove", "StringRedisTemplate.opsForSet.remove"),
                list(store.get("via")).stream().map(JsonNode::asText).toList());
        assertTrue(store.get("file").asText().endsWith("session/SessionStore.java"));
    }
}
