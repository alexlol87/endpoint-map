package com.endpointmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box (#8): functional RouterFunction endpoints, their traced edges and the "routes not understood" report. */
class RouterEndpointsTest {

    static final Path ENV = Path.of("..", "test-environment");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path dir;
    static Map<String, JsonNode> nodes = new HashMap<>();
    static List<JsonNode> edges = new ArrayList<>();
    static String report;

    @BeforeAll
    static void runDiscoverAndMap() throws Exception {
        Path res = dir.resolve("resolutions.yml");
        run(ENV, "discover", "--resolutions", res.toString());
        report = Files.readString(dir.resolve("discover-report.md"));
        run(ENV, "map", "--resolutions", res.toString(), "--out", dir.resolve("out").toString());
        JsonNode g = JSON.readTree(dir.resolve("out/graph.json").toFile());
        g.get("nodes").forEach(n -> nodes.put(n.get("id").asText(), n));
        g.get("edges").forEach(edges::add);
    }

    @Test
    void builderStyleNestingThreeLevelsAcceptAndAdd() {
        endpoint("search-service GET /search", "SearchHandler.search");
        endpoint("search-service GET /search/products", "SearchHandler.products");
        endpoint("search-service GET /search/products/{sku}", "SearchHandler.productBySku"); // accept(..) ignored
        endpoint("search-service GET /search/products/{sku}/similar", "SearchHandler.similar");
        endpoint("search-service GET /search/products/{sku}/bundle", "SearchHandler.bundle");
        endpoint("search-service GET /search/history/{userId}", "SearchHandler.history"); // .nest(path(..), b -> ..)
        endpoint("search-service POST /search/admin/reindex", "SearchHandler.reindex"); // .add(adminRoutes(handler))
        endpoint("search-service DELETE /search/admin/index", "SearchHandler.dropIndex");
        JsonNode lambda = endpoint("search-service POST /search/suggest", "SearchRoutes.searchRouter$lambda@L41");
        assertEquals("search-service/src/main/java/com/acme/search/SearchRoutes.java", lambda.get("file").asText());
        assertEquals(41, lambda.get("line").asInt());
        JsonNode ref = nodes.get("search-service GET /search");
        assertEquals("search-service/src/main/java/com/acme/search/SearchHandler.java", ref.get("file").asText());
    }

    @Test
    void staticStyleAndRouteAndNestAndPrivateRouterMethod() {
        endpoint("recommendation-service GET /recs/{userId}", "RecommendationHandler.forUser"); // GET(..).and(accept(..))
        endpoint("recommendation-service POST /recs/feedback", "RecommendationHandler.feedback");
        endpoint("recommendation-service GET /recs/popular", "RecommendationHandler.popular"); // path(..).and(method(GET))
        endpoint("recommendation-service DELETE /recs/admin/cache", "RecommendationHandler.clearCache");
        endpoint("recommendation-service PUT /recs/admin/models/{model}", "RecommendationRouter.recommendationRoutes$lambda@L42");
        endpoint("recommendation-service GET /recs/v2/users/{userId}/bundle", "RecommendationHandler.bundle"); // .and(v2Routes(..))
    }

    @Test
    void mixedServiceAndBasePathAndConstants() {
        endpoint("loyalty-service POST /loyalty/{userId}/simulate", "LoyaltyRoutes.loyaltyRouter$lambda@L17");
        assertEquals("annotation", nodes.get("loyalty-service GET /loyalty/{userId}/tier").get("source").asText());
        // spring.webflux.base-path + path(CONSTANT, () -> route(GET(CONST + CONST), ..).andRoute(..))
        endpoint("user-profile-service GET /profile-api/profiles/{userId}/summary", "ProfileRoutes.profileRouter$lambda@L27");
        endpoint("user-profile-service GET /profile-api/profiles/ping", "ProfileRoutes.profileRouter$lambda@L30");
        assertEquals("annotation", nodes.get("user-profile-service GET /profile-api/profiles/{userId}").get("source").asText());
    }

    @Test
    void routesBuiltInALoopAreReportedNotDropped() {
        String section = report.substring(report.indexOf("## Routes not understood"));
        assertTrue(section.contains("`search-service/src/main/java/com/acme/search/SearchRoutes.java:60`: router method facetRouter"),
                section);
        assertFalse(nodes.keySet().stream().anyMatch(id -> id.contains("/search/facets")), "loop paths are not guessed");
    }

    @Test
    void edgesFromMethodReferenceHandlersDeepChainsAndLambdas() {
        edge("search-service GET /search/products/{sku}/bundle", "catalog-service GET /catalog/products/{sku}/details",
                "[\"BundleService.bundle\",\"RelatedProductsService.detailsOf\",\"ReactiveHttp.get\",\"WebClient.get\"]");
        edge("search-service GET /search/products/{sku}", "catalog-service GET /catalog/products/{sku}", null);
        edge("search-service GET /search/history/{userId}", "analytics-service GET /analytics/users/{userId}/searches", null);
        edge("search-service POST /search/admin/reindex", "catalog-service GET /catalog/products", null);
        edge("recommendation-service GET /recs/{userId}", "user-profile-service GET /profile-api/profiles/{userId}", null);
        edge("recommendation-service GET /recs/v2/users/{userId}/bundle", "user-profile-service GET /profile-api/profiles/{userId}", null);
        // lambda bodies are traced
        edge("loyalty-service POST /loyalty/{userId}/simulate", "cart-service GET /carts/{userId}/points-preview", null);
        edge("user-profile-service GET /profile-api/profiles/{userId}/summary", "loyalty-service GET /loyalty/{userId}/tier",
                "[\"ProfileService.get\",\"WebClient.get\"]");
    }

    @Test
    void callsIntoRoutesNowResolveToTheirEndpoints() {
        edge("analytics-service GET /analytics/trending", "search-service GET /search/products/{sku}/similar", null);
        edge("analytics-service GET /analytics/export", "search-service GET /search/products", null);
        edge("cart-service GET /carts/{userId}/points-preview", "loyalty-service POST /loyalty/{userId}/simulate", null);
        // the functional-route cycle closes: search <-> analytics, loyalty <-> cart
        edge("search-service GET /search/products/{sku}/similar", "analytics-service GET /analytics/trending", null);
        assertTrue(nodes.keySet().stream().noneMatch(id -> id.startsWith("unresolved:search-service ")
                || id.startsWith("unresolved:loyalty-service ") || id.startsWith("unresolved:recommendation-service ")), nodes.keySet().toString());
    }

    @Test
    void unreadablePartsAreReportedWithFileAndLine(@TempDir Path root) throws Exception {
        Path f = root.resolve("svc/src/main/java/demo/Routes.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                package demo;
                import static org.springframework.web.reactive.function.server.RequestPredicates.*;
                import static org.springframework.web.reactive.function.server.RouterFunctions.route;
                import org.springframework.web.reactive.function.server.*;
                @org.springframework.context.annotation.Configuration
                public class Routes {
                  @org.springframework.context.annotation.Bean
                  RouterFunction<ServerResponse> r(Handler h, HandlerFunction<ServerResponse> fn) {
                    return route(GET("/a").or(GET("/b")), h::a)
                        .andRoute(GET("/c"), fn)
                        .andRoute(POST(System.getenv("P")), h::a)
                        .andRoute(PUT("/d"), h::a);
                  }
                }
                class Handler { Object a(ServerRequest r) { return null; } }
                """);
        Path res = root.resolve("resolutions.yml");
        run(root, "discover", "--resolutions", res.toString());
        String md = Files.readString(root.resolve("discover-report.md"));
        assertTrue(md.contains("`svc/src/main/java/demo/Routes.java:9`: route predicate not understood: GET(\"/a\").or(GET(\"/b\"))"), md);
        assertTrue(md.contains("`svc/src/main/java/demo/Routes.java:10`: handler of GET /c not understood"), md);
        assertTrue(md.contains("`svc/src/main/java/demo/Routes.java:11`: route path not understood: System.getenv(\"P\")"), md);
        run(root, "map", "--resolutions", res.toString(), "--out", root.resolve("out").toString());
        JsonNode g = JSON.readTree(root.resolve("out/graph.json").toFile());
        List<String> ids = new ArrayList<>();
        g.get("nodes").forEach(n -> ids.add(n.get("id").asText()));
        assertTrue(ids.contains("svc PUT /d") && ids.contains("svc GET /c"), ids.toString()); // unknown handler: endpoint kept
    }

    private static JsonNode endpoint(String id, String handler) {
        JsonNode n = nodes.get(id);
        assertNotNull(n, id + " in " + nodes.keySet());
        assertEquals("router", n.get("source").asText(), id);
        assertEquals(handler, n.get("handler").asText(), id);
        return n;
    }

    private static void edge(String from, String to, String via) {
        assertTrue(edges.stream().anyMatch(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals(to)
                && (via == null || e.get("via").toString().equals(via))), from + " -> " + to + " via " + via);
    }

    private static void run(Path root, String... args) {
        List<String> all = new ArrayList<>(List.of(args[0], "--root", root.toString(), "--profile", "prod"));
        all.addAll(List.of(args).subList(1, args.length));
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(0, Main.run(all.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(e, true)), e.toString());
    }
}
