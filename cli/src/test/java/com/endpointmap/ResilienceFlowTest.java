package com.endpointmap;

import static com.endpointmap.FlowTreeTest.paths;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: resilience fallbacks and retries in graph.json (issue #16). */
class ResilienceFlowTest {

    @TempDir
    static Path dir;
    static JsonNode graph;

    @BeforeAll
    static void map() throws Exception {
        String res = dir.resolve("resolutions.yml").toString();
        RepositoryTablesTest.run("discover", RepositoryTablesTest.ENV, "--resolutions", res);
        RepositoryTablesTest.run("map", RepositoryTablesTest.ENV, "--resolutions", res, "--out", dir.resolve("out").toString());
        graph = new ObjectMapper().readTree(dir.resolve("out/graph.json").toFile());
    }

    private static void has(String endpoint, String path) {
        List<String> p = paths(graph, endpoint);
        assertTrue(p.contains(path), p.toString());
    }

    private static boolean edge(String from, String to, String via) {
        for (JsonNode e : graph.get("edges")) {
            if (e.get("from").asText().equals(from) && e.get("to").asText().equals(to) && e.get("via").toString().contains(via)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void circuitBreakerFallbackIsTracedUnderAFallbackNode() {
        String p = "call PriceService.price > call PriceService.taxRate > ";
        has("pricing-service GET /prices/{sku}", p + "fallback @CircuitBreaker > call PriceService.defaultTaxRate > edge tax-service GET /tax-api/tax/rates");
        assertTrue(edge("pricing-service GET /prices/{sku}", "tax-service GET /tax-api/tax/rates", "PriceService.defaultTaxRate"));
        // a fallback reaching a repository query: findLastPriceForSku
        assertTrue(edge("order-service POST /api/orders", "db:order-service:order_lines", "OrderRepository.findLastPriceForSku"));
        assertTrue(paths(graph, "order-service POST /api/orders").stream().anyMatch(s -> s.endsWith(
                "call OrderService.priceOf > fallback @CircuitBreaker > call OrderService.priceFallback > edge db:order-service:order_lines")));
    }

    @Test
    void retryAndRetryableWrapTheMethodAndRecoverIsAFallback() {
        has("pricing-service GET /prices/{sku}", "call PriceService.price > call PriceService.taxRate > retry @Retry(name = \"tax\")"
                + " > edge tax-service GET /tax-api/tax/rates/{region}");
        String r = "call PaymentGateway.charge > retry @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 200)) > ";
        has("payment-service POST /payments/charge", r + "edge external:https://fraud.example-vendor.com GET /v1/score");
        has("payment-service POST /payments/charge", "call PaymentGateway.charge > fallback @Retryable > call PaymentGateway.recoverCharge"
                + " > unresolved unresolved:payment-service:${PAYMENT_HOST} PUT /v1/payment_intents/{*}/cancel");
    }

    @Test
    void fallbackOverloadsAreChosenByParametersAndRecoverByReturnType(@TempDir Path root) throws Exception {
        Path c = root.resolve("rs-service/src/main/java/r");
        RepositoryTablesTest.write(c.resolve("App.java"), "package r;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        RepositoryTablesTest.write(c.resolve("Api.java"), """
                package r;
                import io.github.resilience4j.bulkhead.annotation.Bulkhead;
                import org.springframework.retry.annotation.*;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Api {
                  private final Ext ext;
                  Api(Ext ext) { this.ext = ext; }
                  @GetMapping("/b") @Bulkhead(name = "b", fallbackMethod = "fb") public String b(String id) { return ext.a(); }
                  String fb(String id, IllegalStateException e) { return ext.b(); }
                  String fb(String id, RuntimeException e) { return ext.c(); }
                  String fb(Long id, Throwable e) { return ext.d(); }
                  @GetMapping("/r") @Retryable public String r(String id) { return ext.a(); }
                  @Recover String rec(Exception e, String id) { return ext.b(); }
                  @Recover Integer other(Exception e, String id) { ext.c(); return 1; }
                  @Recover String third(Exception e, Long id) { return ext.d(); }
                }
                """);
        RepositoryTablesTest.write(c.resolve("Ext.java"), """
                package r;
                import org.springframework.web.bind.annotation.GetMapping;
                @org.springframework.cloud.openfeign.FeignClient(name = "ext", url = "https://api.example.com")
                public interface Ext { @GetMapping("/a") String a(); @GetMapping("/b") String b(); @GetMapping("/c") String c(); @GetMapping("/d") String d(); }
                """);
        Path out = root.resolve("out");
        String res = out.resolve("resolutions.yml").toString();
        RepositoryTablesTest.run("discover", root, "--resolutions", res);
        RepositoryTablesTest.run("map", root, "--resolutions", res, "--out", out.toString());
        JsonNode g = new ObjectMapper().readTree(out.resolve("graph.json").toFile());
        String x = "external:https://api.example.com GET /";
        assertEquals(List.of("edge " + x + "a", "fallback @Bulkhead > call Api.fb > edge " + x + "b",
                "fallback @Bulkhead > call Api.fb > edge " + x + "c"), paths(g, "rs-service GET /b"));
        assertEquals(List.of("retry @Retryable > edge " + x + "a", "fallback @Retryable > call Api.rec > edge " + x + "b"), paths(g, "rs-service GET /r"));
    }
}
