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
        JsonNode lambda = endpoint("search-service POST /search/suggest", "SearchRoutes.searchRouter$lambda");
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
        endpoint("recommendation-service PUT /recs/admin/models/{model}", "RecommendationRouter.recommendationRoutes$lambda");
        endpoint("recommendation-service GET /recs/v2/users/{userId}/bundle", "RecommendationHandler.bundle"); // .and(v2Routes(..))
    }

    @Test
    void mixedServiceAndBasePathAndConstants() {
        endpoint("loyalty-service POST /loyalty/{userId}/simulate", "LoyaltyRoutes.loyaltyRouter$lambda");
        assertEquals("annotation", nodes.get("loyalty-service GET /loyalty/{userId}/tier").get("source").asText());
        // spring.webflux.base-path + path(CONSTANT, () -> route(GET(CONST + CONST), ..).andRoute(..))
        endpoint("user-profile-service GET /profile-api/profiles/{userId}/summary", "ProfileRoutes.profileRouter$lambda");
        endpoint("user-profile-service GET /profile-api/profiles/ping", "ProfileRoutes.profileRouter$lambda");
        assertEquals("annotation", nodes.get("user-profile-service GET /profile-api/profiles/{userId}").get("source").asText());
    }

    @Test
    void routesBuiltInALoopAreReportedNotDropped() {
        String section = report.substring(report.indexOf("## Routes not understood"));
        assertTrue(section.contains("`search-service/src/main/java/com/acme/search/SearchRoutes.java:60`: router method facetRouter"),
                section);
        // loop paths are not guessed by the walker; they come only from search-service's OpenAPI (#9)
        assertTrue(nodes.entrySet().stream().filter(n -> n.getKey().contains("/search/facets"))
                .allMatch(n -> n.getValue().get("source").asText().equals("openapi-only")), "loop paths are not guessed");
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
    void aGenericImplementationDoesNotHideTheConcreteOnes(@TempDir Path root) throws Exception {
        Path d = root.resolve("svc/src/main/java/g");
        Files.createDirectories(d);
        Files.writeString(d.resolve("App.java"), "package g;\n@org.springframework.boot.autoconfigure.SpringBootApplication class App {}\n");
        Files.writeString(d.resolve("Handler.java"), "package g;\npublic interface Handler<T> { String handle(T t); }\n");
        for (String[] c : new String[][] {{"LoggingHandler<T>", "Handler<T>", "T", "audit"}, {"OrderHandler", "Handler<Order>", "Order", "orders"},
                {"PaymentHandler", "Handler<Payment>", "Payment", "pay"}}) {
            Files.writeString(d.resolve(c[0].replace("<T>", "") + ".java"), "package g;\npublic class " + c[0] + " implements " + c[1] + " {\n"
                    + "  private final org.springframework.web.client.RestClient web = org.springframework.web.client.RestClient.create(\"https://"
                    + c[3] + ".example.com\");\n  public String handle(" + c[2] + " x) { return web.get().uri(\"/" + c[3]
                    + "\").retrieve().body(String.class); }\n}\n");
        }
        Files.writeString(d.resolve("Order.java"), "package g;\npublic class Order {}\n");
        Files.writeString(d.resolve("Payment.java"), "package g;\npublic class Payment {}\n");
        Files.writeString(d.resolve("Ctl.java"), """
                package g;
                @org.springframework.web.bind.annotation.RestController
                public class Ctl {
                  private final Handler<Order> h;
                  Ctl(Handler<Order> h) { this.h = h; }
                  @org.springframework.web.bind.annotation.GetMapping("/g") String g() { return h.handle(new Order()); }
                }
                """);
        run(root, "map", "--out", root.resolve("out").toString());
        List<String> to = new ArrayList<>();
        JSON.readTree(root.resolve("out/graph.json").toFile()).get("edges").forEach(e -> to.add(e.get("to").asText()));
        // every implementation a Handler<Order> can be is an alternative; PaymentHandler (Handler<Payment>) cannot be one
        for (String impl : List.of("LoggingHandler", "OrderHandler")) {
            assertTrue(to.stream().anyMatch(t -> t.contains(impl + ".handle")), to.toString());
        }
        assertTrue(to.stream().noneMatch(t -> t.contains("PaymentHandler.handle")), to.toString());
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

    @Test
    void splitRouterLocalsHelpersMethodRefsInterfaceHandlerAndNestVerb() {
        // local String/RequestPredicate, a helper filling the builder, a method ref supplying nested routes
        endpoint("recommendation-service GET /recs/split/users/{userId}", "RecommendationHandler.forUser");
        edge("recommendation-service GET /recs/split/users/{userId}", "user-profile-service GET /profile-api/profiles/{userId}", null);
        // handler injected by its interface: its implementation is traced
        endpoint("recommendation-service GET /recs/split/trending", "TrendingHandler.top");
        edge("recommendation-service GET /recs/split/trending", "unresolved:recommendation-service:${CATALOG_URL} GET /catalog/products",
                "[\"TrendingHandlerImpl.top\",\"TrendingHandlerImpl.topIn\",\"CatalogExchange.byCategory\"]");
        endpoint("recommendation-service POST /recs/split/feedback", "RecommendationHandler.feedback"); // nest(POST(..), route(path(..)))
        assertFalse(nodes.containsKey("recommendation-service ANY /recs/split/feedback"));
        assertFalse(report.contains("RecommendationSplitRoutes"), report);
    }

    @Test
    void realisticRouterShapesInATempService(@TempDir Path root) throws Exception {
        Path pkg = root.resolve("shop/src/main/java/com/shop/web");
        Files.createDirectories(pkg);
        Files.createDirectories(root.resolve("shop/src/main/resources"));
        Files.writeString(root.resolve("shop/src/main/resources/application.properties"), "spring.webflux.base-path=/shop\n");
        String imports = """
                package com.shop.web;
                import static org.springframework.web.reactive.function.server.RequestPredicates.*;
                import static org.springframework.web.reactive.function.server.RouterFunctions.*;
                import org.springframework.context.annotation.*;
                import org.springframework.http.MediaType;
                import org.springframework.web.reactive.function.server.*;
                import org.springframework.web.reactive.function.client.WebClient;
                import reactor.core.publisher.Mono;
                """;
        Files.writeString(pkg.resolve("OrderHandler.java"), imports + """
                public class OrderHandler {
                  private final WebClient web = WebClient.create("https://api.stripe.com");
                  public Mono<ServerResponse> list(String q) { return null; }
                  public Mono<ServerResponse> list(ServerRequest r) { return web.get().uri("/v1/list").retrieve().bodyToMono(String.class).then(); }
                  public Mono<ServerResponse> get(ServerRequest r) { return null; }
                  public Mono<ServerResponse> pick(String a) { return null; }
                  public Mono<ServerResponse> pick(Integer a) { return null; }
                }
                """);
        Files.writeString(pkg.resolve("PayHandler.java"), imports + "public interface PayHandler { Mono<ServerResponse> pay(ServerRequest r); }\n");
        Files.writeString(pkg.resolve("PayHandlerImpl.java"), imports + """
                public class PayHandlerImpl implements PayHandler {
                  private final WebClient web = WebClient.create("https://api.stripe.com");
                  public Mono<ServerResponse> pay(ServerRequest r) { return web.post().uri("/v1/charges").retrieve().bodyToMono(String.class).then(); }
                }
                """);
        Files.writeString(pkg.resolve("NoImpl.java"), imports + "public interface NoImpl { Mono<ServerResponse> run(ServerRequest r); }\n");
        Files.writeString(pkg.resolve("BaseHandler.java"), imports
                + "public abstract class BaseHandler { public Mono<ServerResponse> health(ServerRequest r) { return null; } }\n");
        Files.writeString(pkg.resolve("ItemHandler.java"), imports + "public class ItemHandler extends BaseHandler { }\n");
        Files.writeString(pkg.resolve("Routes.java"), imports + """
                @Configuration
                public class Routes {
                  @Bean
                  RouterFunction<ServerResponse> orders(OrderHandler h) {
                    return nest(path("/api"), route(GET("/orders"), h::list)
                        .andRoute(org.springframework.web.reactive.function.server.RequestPredicates.POST("/orders"), h::get));
                  }
                  @Bean
                  RouterFunction<ServerResponse> composed(RouterFunction<ServerResponse> orders, ItemHandler items, PayHandler pay, NoImpl none, OrderHandler h) {
                    return RouterFunctions.route()
                        .path("/m", b -> b.add(orders))
                        .GET("/health", items::health)
                        .POST("/pay", pay::pay)
                        .GET("/none", none::run)
                        .GET("/pick", h::pick)
                        .GET(System.getenv("X"), h::get)
                        .path("/b", b -> common(b, h))
                        .nest(path("/c"), this::moreRoutes)
                        .nest(POST("/post-only"), b -> b.route(path("/a"), h::get).GET("/clash", h::get))
                        .build();
                  }
                  private RouterFunctions.Builder common(RouterFunctions.Builder b, OrderHandler h) { return b.GET("/x", h::get); }
                  private void moreRoutes(RouterFunctions.Builder b) { b.GET("/y", r -> null); }
                  @Bean
                  RouterFunction<ServerResponse> withLocals(OrderHandler h) {
                    RequestPredicate json = accept(MediaType.APPLICATION_JSON);
                    String base = "/locals";
                    RouterFunctions.Builder b = route();
                    b.GET(base + "/one", json, h::get);
                    RouterFunction<ServerResponse> more = route(PUT(base).and(json), h::get);
                    return b.build().and(more).and(nest(POST("/nv"), route(path("/a"), h::get)));
                  }
                }
                """);
        Path res = root.resolve("resolutions.yml");
        run(root, "discover", "--resolutions", res.toString());
        String md = Files.readString(root.resolve("discover-report.md"));
        run(root, "map", "--resolutions", res.toString(), "--out", root.resolve("out").toString());
        JsonNode g = JSON.readTree(root.resolve("out/graph.json").toFile());
        Map<String, String> handlers = new HashMap<>();
        g.get("nodes").forEach(n -> handlers.put(n.get("id").asText(), n.path("handler").asText()));
        List<String> links = new ArrayList<>();
        g.get("edges").forEach(e -> links.add(e.get("from").asText() + " -> " + e.get("to").asText() + " " + e.get("via")));
        String S = "shop GET /shop";
        // overload: the ServerRequest one, traced
        assertEquals("OrderHandler.list", handlers.get(S + "/api/orders"));
        assertTrue(links.contains("shop GET /shop/api/orders -> external:https://api.stripe.com GET /v1/list [\"WebClient.get\"]"), links.toString());
        assertTrue(handlers.containsKey("shop POST /shop/api/orders"), "fully qualified RequestPredicates");
        // interface handler: its implementation is traced
        assertEquals("PayHandler.pay", handlers.get("shop POST /shop/pay"));
        assertTrue(links.contains("shop POST /shop/pay -> external:https://api.stripe.com POST /v1/charges [\"PayHandlerImpl.pay\",\"WebClient.post\"]"),
                links.toString());
        assertTrue(md.contains("tracing shop GET /shop/none: its handler NoImpl.run has no body and no implementation was found"), md);
        assertEquals("BaseHandler.health", handlers.get(S + "/health")); // inherited
        assertEquals("OrderHandler.list", handlers.get(S + "/m/api/orders")); // injected RouterFunction bean, prefixed
        assertTrue(handlers.containsKey("shop POST /shop/m/api/orders"));
        assertEquals("OrderHandler.get", handlers.get(S + "/b/x")); // Builder helper
        assertEquals("Routes.moreRoutes$lambda", handlers.get(S + "/c/y")); // method ref Consumer<Builder>
        assertEquals("OrderHandler.get", handlers.get("shop POST /shop/post-only/a")); // nest verb carried down
        assertEquals("OrderHandler.get", handlers.get(S + "/locals/one")); // builder, String and predicate locals
        assertEquals("OrderHandler.get", handlers.get("shop PUT /shop/locals"));
        assertEquals("OrderHandler.get", handlers.get("shop POST /shop/nv/a"));
        assertTrue(handlers.keySet().stream().noneMatch(id -> id.startsWith("shop ANY ") || id.contains("/clash")), handlers.keySet().toString());
        assertTrue(md.contains("`shop/src/main/java/com/shop/web/Routes.java:27`: route GET /clash inside a POST-only nest matches no request"), md);
        assertTrue(md.contains("`shop/src/main/java/com/shop/web/Routes.java:24`: route path not understood: System.getenv(\"X\")"), md);
        assertTrue(md.contains("handler of GET /shop/pick not understood (endpoint kept, not traced): h::pick"), md); // two overloads, no ServerRequest
        assertFalse(md.contains("not a single") || md.contains("router expression not understood") || md.contains("builder not understood"), md);
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
