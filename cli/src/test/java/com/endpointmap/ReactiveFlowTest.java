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

/** Black-box: reactive operators as control flow in graph.json {@code flows} (issue #14). */
class ReactiveFlowTest {

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

    @Test
    void flatMapAndConcatMapOverAFluxAreLoopsButFlatMapOnAMonoIsNot() {
        assertEquals(List.of("call ProductService.batch > loop skus.flatMap > edge db:catalog-service:products",
                        "call ProductService.batch > loop skus.flatMap(..).concatMap > edge inventory-service GET /inventory/{sku}",
                        "call ProductService.batch > loop skus.flatMap(..).concatMap(..).flatMap > edge db:catalog-service:products"),
                paths(graph, "catalog-service POST /catalog/products/batch"));
        has("catalog-service GET /catalog/products/{sku}", "call ProductService.getWithPrice > edge pricing-service GET /prices/{sku}");
    }

    @Test
    void zipAndWhenAreParallel() {
        String d = "call ProductDetailsHandler.details > call ProductDetailsService.details > parallel Mono.zip > ";
        has("catalog-service GET /catalog/products/{sku}/details", d + "call AvailabilityService.available > call StockLookupService.stock"
                + " > call ReactiveClientUtils.getJson > edge inventory-service GET /inventory/{sku}");
        has("catalog-service GET /catalog/products/{sku}/details", d + "call PriceLookupService.price > call ReactiveClientUtils.getJson"
                + " > edge pricing-service GET /prices/{sku}");
        // publishers held in local variables, then zipped
        has("cart-service GET /carts/{userId}", "call CartService.view > parallel Mono.zip > edge loyalty-service GET /loyalty/{userId}/balance");
        has("cart-service GET /carts/{userId}", "call CartService.view > parallel Mono.zip > edge redis:cart:*");
        has("analytics-service GET /analytics/trending", "call AnalyticsService.trending > parallel Flux.zip > else !(sku == null)"
                + " > retry retryWhen(Retry.fixedDelay(2, Duration.ofMillis(50))) > edge search-service GET /search/products/{sku}/similar");
        has("recommendation-service GET /recs/v2/users/{userId}/bundle", "parallel Mono.when > edge user-profile-service GET /profile-api/profiles/{userId}");
    }

    @Test
    void switchIfEmptyAndOnErrorResumeAreFallbackBranches() {
        has("audit-service GET /audit/events/{id}", "call AuditService.get > fallback switchIfEmpty > edge external:https://api.siem-vendor.example.com GET /v2/events/{id}");
        has("review-service GET /reviews/product/{sku}", "call ReviewService.forProduct > fallback onErrorResume > edge db:review-service:reviews_archive");
        has("review-service GET /reviews/product/{sku}", "call ReviewService.forProduct > edge db:review-service:reviews");
    }

    @Test
    void filterIsAnIfAndRetryWrapsWhatItRetries() {
        has("analytics-service POST /analytics/events", "call AnalyticsService.track > if filter(e -> e.get(\"userId\") != null) > edge redis:searches:*");
        has("review-service POST /reviews", "call ReviewService.create > retry retry(2) > edge db:review-service:product_ratings");
        has("review-service POST /reviews", "call ReviewService.create > retry retryWhen(Retry.backoff(3, Duration.ofMillis(100)))"
                + " > edge catalog-service GET /catalog/products/{sku}");
    }

    @Test
    void fluxSourcesAndEagerFallbackArgumentsInASmallService(@TempDir Path root) throws Exception {
        Path c = root.resolve("rx-service/src/main/java/r");
        RepositoryTablesTest.write(c.resolve("App.java"), "package r;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        RepositoryTablesTest.write(c.resolve("Api.java"), """
                package r;
                import reactor.core.publisher.Flux;
                import reactor.core.publisher.Mono;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Api {
                  private final Ext ext;
                  Api(Ext ext) { this.ext = ext; }
                  private final Flux<String> top = Flux.just("t");
                  private final org.springframework.web.reactive.function.client.WebClient wc =
                      org.springframework.web.reactive.function.client.WebClient.create("https://w.example.com");
                  @GetMapping("/s") public Object s() {
                    var local = Flux.fromIterable(java.util.List.of("a"));
                    return Flux.concat(ids().flatMap(i -> ext.b()), this.ids().concatMap(i -> ext.c()), ext.list().flatMap(i -> ext.d()),
                        wc.get().uri("/l").retrieve().bodyToFlux(String.class).flatMap(i -> ext.a()), top.flatMap(t -> ext.b()),
                        local.flatMap(i -> ext.c()), Mono.just("m").flatMap(top -> Mono.just(top).flatMap(v -> ext.d())));
                  }
                  Flux<String> ids() { return Flux.just("i"); }
                  @GetMapping("/r") public Object r() {
                    Flux<String> ids = Flux.just("a");
                    return Flux.zip(ext.a(), ext.b())
                        .then(Mono.just("x").defaultIfEmpty(ext.c()))
                        .onErrorReturn(ext.d())
                        .thenMany(ids.map(i -> ext.a()));
                  }
                }
                """);
        RepositoryTablesTest.write(c.resolve("Ext.java"), """
                package r;
                import org.springframework.web.bind.annotation.GetMapping;
                @org.springframework.cloud.openfeign.FeignClient(name = "ext", url = "https://api.example.com")
                public interface Ext { @GetMapping("/l") reactor.core.publisher.Flux<String> list(); @GetMapping("/a") String a(); @GetMapping("/b") String b(); @GetMapping("/c") String c(); @GetMapping("/d") String d(); }
                """);
        Path out = root.resolve("out");
        String res = out.resolve("resolutions.yml").toString();
        RepositoryTablesTest.run("discover", root, "--resolutions", res);
        RepositoryTablesTest.run("map", root, "--resolutions", res, "--out", out.toString());
        String x = "external:https://api.example.com GET /";
        assertEquals(List.of("parallel Flux.zip > edge " + x + "a", "parallel Flux.zip > edge " + x + "b",
                        "edge " + x + "c", "edge " + x + "d", "loop ids.map > edge " + x + "a"),
                paths(new ObjectMapper().readTree(out.resolve("graph.json").toFile()), "rx-service GET /r"));
        // a Flux from a workspace method, a Feign method, bodyToFlux, a field, a var; a lambda parameter shadowing the field is no Flux
        assertEquals(List.of("loop ids().flatMap > edge " + x + "b", "loop this.ids().concatMap > edge " + x + "c", "edge " + x + "l",
                        "loop ext.list().flatMap > edge " + x + "d", "unresolved unresolved:rx-service:Api.s:15 GET /l",
                        "loop wc.get().uri(..).retrieve().bodyToFlux(..).flatMap > edge " + x + "a", "loop top.flatMap > edge " + x + "b",
                        "loop local.flatMap > edge " + x + "c", "edge " + x + "d"), paths(new ObjectMapper().readTree(out.resolve("graph.json").toFile()), "rx-service GET /s"));
    }
}
