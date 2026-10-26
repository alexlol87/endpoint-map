package com.endpointmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: WebClient and RestClient calls become edges, unknown URLs land in resolutions.yml (issue #5). */
class WebClientRestClientTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final Path ENV = Path.of("..", "test-environment");
    static final String DETAILS = "catalog-service GET /catalog/products/{sku}/details";
    static final String MODERATE = "review-service POST /reviews/{id}/moderate";

    @TempDir
    static Path dir;
    static List<JsonNode> edges;
    static String resolutions;

    @BeforeAll
    static void discoverAndMap() throws Exception {
        Path res = dir.resolve("resolutions.yml");
        run("discover", ENV, "--resolutions", res.toString());
        resolutions = Files.readString(res);
        edges = map(ENV, "prod", res, dir.resolve("out"));
    }

    @Test
    void webClientBaseUrlFollowsTheInjectedFieldToItsBeanByQualifierOrBeanName() {
        // @Qualifier("pricingWebClient") = the @Bean method's name; ${clients.pricing.base-url} = http://localhost:8084
        JsonNode e = edge("catalog-service GET /catalog/products/{sku}", "pricing-service GET /prices/{sku}");
        assertEquals("webclient", e.get("client").asText());
        assertEquals("http", e.get("kind").asText());
        assertEquals(List.of("ProductService.getWithPrice", "WebClient.get"), strings(e.get("via")));
        assertEquals("catalog-service/src/main/java/com/acme/catalog/ProductService.java", e.get("file").asText());
        assertEquals(35, e.get("line").asInt());
        // @Bean("inventoryWebClient") + ${INVENTORY_URL:http://localhost:8083}: the default
        edge("catalog-service POST /catalog/products/batch", "inventory-service GET /inventory/{sku}");
        // @Qualifier("loyalty") on the @Bean method itself
        edge("user-profile-service GET /profile-api/profiles/{userId}", "loyalty-service GET /loyalty/{userId}/tier");
        // a field that has no @Qualifier picks the bean named like it, among two WebClient beans
        assertEquals(List.of("ModerationHandler.moderate", "ModerationService.moderate", "ProfileSync.flagUser",
                "WebClientCalls.send", "WebClient.method"), strings(edge(MODERATE, "user-profile-service PATCH /profile-api/profiles/{userId}").get("via")));
        // WebClient.create(url) / builder().baseUrl(url) assigned in the constructor from @Value parameters
        edge("cart-service POST /carts/{userId}/items", "catalog-service GET /catalog/products/{sku}");
        edge("cart-service GET /carts/{userId}", "loyalty-service GET /loyalty/{userId}/balance");
        edge("review-service POST /reviews", "catalog-service GET /catalog/products/{sku}");
    }

    @Test
    void restClientCreateBuilderUriFunctionAndAbsoluteUri() {
        // builder().baseUrl(@Value ${tax.url}) from the prod profile document: tax-service.prod.internal/tax-api + HttpPaths constant
        JsonNode tax = edge("pricing-service GET /prices/{sku}", "tax-service GET /tax-api/tax/rates/{region}");
        assertEquals("restclient", tax.get("client").asText());
        assertEquals(List.of("PriceService.price", "PriceService.taxRate", "RestClient.get"), strings(tax.get("via")));
        // @Value("${fx.url:https://api.exchangerate.host}") + uri(b -> b.path("/convert").queryParam(..).build())
        assertEquals("external", edge("pricing-service GET /prices/{sku}", "external:https://api.exchangerate.host GET /convert")
                .get("kind").asText());
        // RestClient.create(literal), builder().baseUrl(literal), concatenation with a variable, absolute uri on create()
        assertEquals(Set.of("external:https://api-eu.dhl.com POST /shipping/v2/orders",
                        "external:https://labels.example-print.com POST /pdf/{id}",
                        "external:https://onlinetools.ups.com POST /api/shipments/v1/ship"),
                targetsOf("shipping-service POST /shipments", "external:"));
        edge("shipping-service GET /shipments/{id}", "external:https://onlinetools.ups.com GET /api/track/v1/details/{*}");
        edge("shipping-service DELETE /shipments/{id}", "external:https://api-eu.dhl.com DELETE /shipping/v2/orders/{id}");
        edge("customer-service GET /customers-api/customers/{id}/orders-summary",
                "order-service GET /api/orders/by-customer/{customerId}/summary");
        // RestClient inside Mono.fromCallable
        edge("analytics-service GET /analytics/users/{userId}/searches", "warehouse-service GET /warehouse/stock/hot");
    }

    @Test
    void deepCatalogChainFollowsEachWebClientBeanPassedIntoTheSameStaticUtil() {
        assertEquals(Set.of("inventory-service GET /inventory/{sku}", "pricing-service GET /prices/{sku}"), targetsOf(DETAILS, ""));
        JsonNode stock = edge(DETAILS, "inventory-service GET /inventory/{sku}");
        assertEquals(List.of("ProductDetailsHandler.details", "ProductDetailsService.details", "AvailabilityService.available",
                "StockLookupService.stock", "ReactiveClientUtils.getJson", "WebClient.get"), strings(stock.get("via")));
        assertEquals("catalog-service/src/main/java/com/acme/catalog/details/ReactiveClientUtils.java", stock.get("file").asText());
        assertEquals(List.of("ProductDetailsHandler.details", "ProductDetailsService.details", "PriceLookupService.price",
                "ReactiveClientUtils.getJson", "WebClient.get"), strings(edge(DETAILS, "pricing-service GET /prices/{sku}").get("via")));
        assertFalse(stock.has("confidence"), "one method of that name on the declared type is certain");
    }

    @Test
    void urlsFromStaticHelpersUriComponentsBuilderStringFormatAndVerbArguments() {
        // client.method(method).uri(uri): bean, HttpMethod.PATCH and ModerationPaths.profile(userId) all from the caller
        JsonNode profile = edge(MODERATE, "user-profile-service PATCH /profile-api/profiles/{userId}");
        assertEquals("webclient", profile.get("client").asText());
        // .patch() + UriComponentsBuilder.fromPath(..).pathSegment(sku, "quantity").queryParam(..)
        assertEquals(List.of("ModerationHandler.moderate", "ModerationService.moderate", "StockSync.release", "WebClient.patch"),
                strings(edge(MODERATE, "inventory-service PATCH /inventory/{sku}/quantity").get("via")));
        // RestClient .patch() + String.format("%s/customers/%d/preferences", @Value field, id)
        assertEquals("restclient", edge(MODERATE, "customer-service PATCH /customers-api/customers/{id}/preferences")
                .get("client").asText());
        // wrappers from libraries with the URL and verb as arguments (ApiGateway): @Value field + constant, getter + concat
        edge("billing-service POST /billing/invoices", "tax-service POST /tax-api/tax/calculate");
        edge("billing-service POST /billing/invoices", "shipping-service GET /shipments/{id}");
    }

    @Test
    void configurationPropertiesAndHostsNotMappedToAServiceBecomeExternal() throws Exception {
        // record @ConfigurationProperties("warehouse.clients").inventoryUrl(): prod is an internal host of no service
        edge("warehouse-service POST /warehouse/dispatch/{sku}", "external:https://inventory.prod.acme.internal PUT /inventory/{sku}/reserve");
        edge("audit-service POST /audit/events", "external:https://api.siem-vendor.example.com POST /v2/events");
        edge("notification-service POST /notifications/{userId}", "external:https://api.sendgrid.com POST /v3/mail/send");
        // the default profile names inventory-service by its port
        List<JsonNode> dev = map(ENV, "dev", dir.resolve("none.yml"), dir.resolve("out-dev"));
        assertTrue(dev.stream().anyMatch(e -> e.get("from").asText().equals("warehouse-service POST /warehouse/dispatch/{sku}")
                && e.get("to").asText().equals("inventory-service PUT /inventory/{sku}/reserve")));
    }

    @Test
    void unresolvableBaseUrlsArePropertiesAndRuntimeUrlsAreCallSites() throws Exception {
        JsonNode y = YAML.readTree(resolutions);
        // clients.audit-url=${AUDIT_SERVICE_URL}: the environment variable is what is missing
        assertTrue(y.get("user-profile-service").get("properties").has("${AUDIT_SERVICE_URL}"), resolutions);
        assertTrue(resolutions.contains("# hint: url = \"${clients.audit-url}/audit/events\"; environment variable"
                + " ${AUDIT_SERVICE_URL} not set"), resolutions);
        assertTrue(y.get("review-service").get("properties").has("clients.moderation-vendor-url"), resolutions);
        assertTrue(y.get("review-service").get("callSites").has("VendorSync.report:26"), resolutions);
        assertTrue(resolutions.contains("# hint: url = String.valueOf(body.get(\"callbackUrl\")) (not readable from code)"), resolutions);
        assertTrue(targetsOf(MODERATE, "unresolved:").contains("unresolved:review-service:VendorSync.report:26 POST {*}"));

        Path res = dir.resolve("edited.yml");
        Files.writeString(res, resolutions
                .replace("\"${AUDIT_SERVICE_URL}\": \"\"", "\"${AUDIT_SERVICE_URL}\": service:audit-service")
                .replace("clients.moderation-vendor-url: \"\"", "clients.moderation-vendor-url: external:https://vendor.example.com"));
        List<JsonNode> mapped = map(ENV, "prod", res, dir.resolve("out-edited"));
        assertTrue(has(mapped, "user-profile-service PATCH /profile-api/profiles/{userId}", "audit-service POST /audit/events"));
        assertTrue(has(mapped, MODERATE, "external:https://vendor.example.com POST /v1/reports"));
    }

    @Test
    void argumentsPropagateThreeCallLevelsThenTheCallSiteIsAsked(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Api.java"), """
                package a;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.reactive.function.client.WebClient;
                @RestController
                public class Api {
                  private final WebClient client = WebClient.create();
                  @org.springframework.beans.factory.annotation.Value("${b.url}") private String bUrl;
                  @GetMapping("/three") public void three() { l1("http://svc-b/items"); }
                  @GetMapping("/four") public void four() { k1("http://svc-b/items"); }
                  @GetMapping("/prop") public void prop() { client.get().uri(bUrl + "/items").retrieve(); }
                  void l1(String u) { l2(u); }
                  void l2(String u) { l3(u); }
                  void l3(String u) { client.get().uri(u).retrieve(); }
                  void k1(String u) { k2(u); }
                  void k2(String u) { k3(u); }
                  void k3(String u) { k4(u); }
                  void k4(String u) { client.get().uri(u).retrieve(); }
                }
                """);
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(b.resolve("Api.java"), "package b;\n@org.springframework.web.bind.annotation.RestController\npublic class Api {\n"
                + "  @org.springframework.web.bind.annotation.GetMapping(\"/items\") public void items() { }\n}\n");
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        List<JsonNode> g = map(root, "prod", res, root.resolve("out"));
        assertTrue(has(g, "svc-a GET /three", "svc-b GET /items"), g.toString());
        assertTrue(has(g, "svc-a GET /four", "unresolved:svc-a:Api.k4:17 GET {*}"), g.toString());
        JsonNode y = YAML.readTree(res.toFile()).get("svc-a");
        assertEquals(Set.of("Api.k4:17"), names(y.get("callSites")));
        assertEquals(Set.of("b.url"), names(y.get("properties")));

        // a user's own comment that looks like a generated one is kept, exactly once, across re-runs
        Files.writeString(res, Files.readString(res).replace("    b.url:",
                "    # used by the nightly import too\n    # hint: ask the platform team\n    b.url:"));
        run("discover", root, "--resolutions", res.toString());
        run("discover", root, "--resolutions", res.toString());
        String text = Files.readString(res);
        assertEquals(1, text.split("# used by the nightly import too", -1).length - 1, text);
        assertEquals(1, text.split("# hint: ask the platform team", -1).length - 1, text);
        assertEquals(2, text.split("# hint: url = ", -1).length - 1, text); // b.url's and Api.k4's, once each
        assertTrue(text.contains("    # used by the nightly import too\n    # hint: ask the platform team\n    # used by Api.prop"), text);
    }

    // --- helpers ---

    private static JsonNode edge(String from, String to) {
        return edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals(to))
                .findFirst().orElseThrow(() -> new AssertionError("no edge " + from + " -> " + to + " in " + targetsOf(from, "")));
    }

    private static Set<String> targetsOf(String from, String prefix) {
        Set<String> out = new TreeSet<>();
        edges.stream().filter(e -> e.get("from").asText().equals(from)).map(e -> e.get("to").asText())
                .filter(t -> t.startsWith(prefix)).forEach(out::add);
        return out;
    }

    private static boolean has(List<JsonNode> edges, String from, String to) {
        return edges.stream().anyMatch(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals(to));
    }

    private static List<JsonNode> map(Path root, String profile, Path res, Path out) throws Exception {
        List<String> args = new ArrayList<>(List.of("map", "--root", root.toString(), "--profile", profile, "--out", out.toString()));
        if (Files.exists(res)) {
            args.addAll(List.of("--resolutions", res.toString()));
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args.toArray(String[]::new), new PrintStream(o, true), new PrintStream(e, true)), e.toString());
        JsonNode g = JSON.readTree(out.resolve("graph.json").toFile());
        Set<String> ids = new TreeSet<>();
        g.get("nodes").forEach(n -> ids.add(n.get("id").asText()));
        List<JsonNode> list = new ArrayList<>();
        g.get("edges").forEach(edge -> {
            assertTrue(ids.contains(edge.get("to").asText()), "edge target is a node: " + edge);
            list.add(edge);
        });
        return list;
    }

    private static void run(String command, Path root, String... more) {
        List<String> args = new ArrayList<>(List.of(command, "--root", root.toString(), "--profile", "prod"));
        args.addAll(List.of(more));
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(e, true)), e.toString());
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
