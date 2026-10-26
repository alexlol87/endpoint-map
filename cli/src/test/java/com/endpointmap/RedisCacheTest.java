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
    static String report;

    @BeforeAll
    static void map() throws Exception {
        String res = dir.resolve("resolutions.yml").toString();
        run("discover", RepositoryTablesTest.ENV, "--resolutions", res);
        report = java.nio.file.Files.readString(dir.resolve("discover-report.md"));
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
        // calculate calls rate(..) on this: no cache
        assertEquals(List.of("redis:tax:audit list.rightPush", "redis:tax:leaderboard zset.add",
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
        // a key with two possible prefixes gives both; current(..) called on this bypasses the cache proxy: no cache.get,
        // but the store it reads is still traced
        assertEquals(List.of("redis:* delete", "redis:guest:* delete", "redis:session-tokens::* cache.evict", "redis:session:* delete",
                "redis:session:* expire", "redis:session:* hash.entries", "redis:session:active set.remove",
                "redis:sessions::* cache.evict"), redis(from));
        assertTrue(report.contains("- `" + from + "`: delete at customer-service/src/main/java/com/acme/customer/session/SessionStore.java:30"),
                "unknown key listed in the report");
        JsonNode store = edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals("redis:session:active"))
                .findFirst().orElseThrow();
        assertEquals(List.of("SessionHandler.end", "SessionService.logout", "SessionStore.remove", "StringRedisTemplate.opsForSet.remove"),
                list(store.get("via")).stream().map(JsonNode::asText).toList());
        assertTrue(store.get("file").asText().endsWith("session/SessionStore.java"));
    }

    @Test
    void cacheNameNotKnownFromCodeIsRedisStarNotAFakeName(@TempDir Path root) throws Exception {
        Path s = root.resolve("s-service/src/main/java/s");
        RepositoryTablesTest.write(s.resolve("App.java"), "package s;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        RepositoryTablesTest.write(s.resolve("Ctl.java"), """
                package s;
                import org.springframework.web.bind.annotation.*;
                @RestController class Ctl {
                    private final Svc svc;
                    Ctl(Svc svc) { this.svc = svc; }
                    @GetMapping("/x") String x() { return svc.load(); }
                }
                @org.springframework.stereotype.Service class Svc {
                    @org.springframework.cache.annotation.Cacheable(cacheNames = Missing.NAME) String load() { return ""; }
                }
                """);
        String res = root.resolve("r/resolutions.yml").toString();
        run("discover", root, "--resolutions", res);
        run("map", root, "--resolutions", res, "--out", root.resolve("out").toString());
        JsonNode graph = new ObjectMapper().readTree(root.resolve("out/graph.json").toFile());
        assertEquals(List.of("redis:* cache.get"), list(graph.get("edges")).stream()
                .map(e -> e.get("to").asText() + " " + e.get("op").asText()).toList());
        assertTrue(java.nio.file.Files.readString(root.resolve("r/discover-report.md")).contains("- `s-service GET /x`: cache.get at "));
    }
}
