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

/** Black-box: calls through wrappers and shared client libraries (issue #7). */
class WrapperClientsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final Path ENV = Path.of("..", "test-environment");
    static final String INVOICES = "billing-service POST /billing/invoices";
    static final String SYNC = "billing-service POST /billing/tenants/{tenant}/sync";
    static final String RESTOCK = "warehouse-service POST /warehouse/restock/{sku}";
    static final String NOTIFY = "notification-service POST /notifications/{userId}";

    @TempDir
    static Path dir;
    static List<JsonNode> edges;
    static JsonNode resolutions;

    @BeforeAll
    static void discoverAndMap() throws Exception {
        Path res = dir.resolve("resolutions.yml");
        run("discover", ENV, "--resolutions", res.toString());
        resolutions = YAML.readTree(res.toFile());
        edges = map(ENV, res, dir.resolve("out"));
    }

    @Test
    void libraryWrapperTakesUrlAndVerbFromTheCallerAndIsNamedAsTheClient() {
        // ApiGateway.post(@Value taxUrl, constant path): base + path both parameters of the wrapper
        JsonNode tax = edge(INVOICES, "tax-service POST /tax-api/tax/calculate");
        assertEquals("ApiGateway", tax.get("client").asText());
        assertEquals(List.of("BillingService.createInvoice", "ApiGateway.post", "RestClient.post"), strings(tax.get("via")));
        assertEquals("lib-http-clients/src/main/java/com/acme/libs/http/ApiGateway.java", tax.get("file").asText());
        // ApiGateway.call(HttpMethod.GET, getter + HttpPaths constant + id): the verb is a parameter too
        assertEquals("ApiGateway", edge(INVOICES, "shipping-service GET /shipments/{id}").get("client").asText());
        // InternalRestTemplateClient (RestTemplate) and ReactiveHttp (WebClient)
        assertEquals("InternalRestTemplateClient", edge("report-service GET /reports/{type}", "order-service GET /api/orders/search")
                .get("client").asText());
        assertEquals("ReactiveHttp", edge(RESTOCK, "inventory-service GET /inventory/{sku}").get("client").asText());
        // a client call in the service's own code keeps the client's name
        assertEquals("resttemplate", edge("order-service POST /api/orders/restock/{sku}", "inventory-service GET /inventory/{sku}")
                .get("client").asText());
    }

    @Test
    void argumentsPropagateThreeCallsDeepTheFourthIsACallSite() {
        // threeLevels -> relay -> fetch: the URL is passed through 3 calls, still known
        assertEquals(List.of("BillingService.syncTenant", "DeepChainCaller.threeLevels", "DeepChainCaller.relay",
                "InternalRestTemplateClient.fetch", "RestTemplate.getForObject"), strings(edge(SYNC, "shipping-service GET /shipments/{id}").get("via")));
        // fourLevels -> relayTwice -> relayOnce -> remove: the same URL through 4 calls is lost
        assertEquals(List.of("BillingService.syncTenant", "DeepChainCaller.fourLevels", "DeepChainCaller.relayTwice",
                "DeepChainCaller.relayOnce", "InternalRestTemplateClient.remove", "RestTemplate.delete"),
                strings(edge(SYNC, "unresolved:billing-service:InternalRestTemplateClient.remove:34 DELETE {*}").get("via")));
        assertFalse(targets(SYNC).contains("shipping-service DELETE /shipments/{id}"), targets(SYNC).toString());
        // level1..level4 -> fetch: deeper still
        edge(SYNC, "unresolved:billing-service:InternalRestTemplateClient.fetch:18 GET {*}");
        JsonNode sites = resolutions.get("billing-service").get("callSites");
        assertEquals(Set.of("InternalRestTemplateClient.fetch:18", "InternalRestTemplateClient.remove:34",
                "InternalRestTemplateClient.send:26"), names(sites));
    }

    @Test
    void libraryUrlHelpersAndRuntimeUrls() {
        // UrlTemplates.invoiceUrl: UriComponentsBuilder.fromUriString(base).path("/billing/invoices/{id}") + "/history"
        edge("billing-service GET /billing/invoices/{id}", "unresolved:billing-service GET /billing/invoices/{id}/history");
        // UrlTemplates.tenantUrl: String.format("%s/tenants/%s%s", ..)
        edge(SYNC, "unresolved:shipping-service GET /tenants/{*}/shipments");
        // UrlTemplates.join(taxUrl, "tax", "rates", s): StringBuilder appends in a loop over the varargs
        edge(SYNC, "unresolved:tax-service PUT /tax-api/tax/rates/{*}");
        // a URL taken from the request body: a callSites entry keyed Class.method:line
        edge(SYNC, "unresolved:billing-service:InternalRestTemplateClient.send:26 POST {*}");
        assertTrue(resolutions.get("report-service").get("callSites").has("InternalRestTemplateClient.send:26"));
    }

    @Test
    void wrapperObjectsBuiltWithNewCarryTheirConstructorArguments() {
        // this.inventory = new ReactiveHttp(WebClient.create(@Value stockUrl)) in the constructor; get and send(PATCH)
        assertEquals(Set.of("audit-service POST /audit/events", "billing-service POST /billing/invoices",
                "inventory-service GET /inventory/{sku}", "inventory-service PATCH /inventory/{sku}/quantity"), targets(RESTOCK));
        assertEquals(List.of("RestockCoordinator.restock", "ReactiveHttp.send", "WebClient.method"),
                strings(edge(RESTOCK, "inventory-service PATCH /inventory/{sku}/quantity").get("via")));
        // injected ReactiveHttp: the @Bean method builds it with new ReactiveHttp(builder().baseUrl(@Value auditUrl))
        // new ReactiveHttp("http://localhost:8087").post(..): a receiver built inline, through this(WebClient.create(url))
        assertEquals("ReactiveHttp", edge(RESTOCK, "billing-service POST /billing/invoices").get("client").asText());
        // @Bean new SmsNotifier(@Value selfUrl): the service calls itself; /sms also fits /{userId}: both, never one guess
        JsonNode sms = edge(NOTIFY, "notification-service POST /notifications/sms");
        assertEquals("SmsNotifier", sms.get("client").asText());
        assertEquals(List.of("SmsNotifier.notify", "WebClient.post"), strings(sms.get("via")));
        edge(NOTIFY, "notification-service POST /notifications/{userId}");
        assertFalse(resolutions.get("notification-service").get("callSites").has("SmsNotifier.notify:17"), resolutions.toString());
        // a field initializer WebClient.create(literal) in a library class
        assertEquals("EmailNotifier", edge(NOTIFY, "external:https://api.sendgrid.com POST /v3/mail/send").get("client").asText());
    }

    @Test
    void ambiguousMatchesAreAllCandidates() {
        // getNotificationUrl() + "/notifications/" + customerId: /notifications/{*} fits three endpoints
        assertEquals(Set.of("notification-service POST /notifications/broadcast", "notification-service POST /notifications/sms",
                "notification-service POST /notifications/{userId}"), targets(INVOICES, "notification-service"));
        for (String t : targets(INVOICES, "notification-service")) {
            assertEquals("InternalRestTemplateClient", edge(INVOICES, t).get("client").asText());
        }
    }

    @Test
    void aCycleWalkedWithTwoArgumentValuesKeepsBoth(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Api.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  private final S s;
                  Api(S s) { this.s = s; }
                  @org.springframework.web.bind.annotation.GetMapping("/c") void c() { s.a("/b/one"); }
                }
                """);
        // a -> b(u) -> a (back edge), then a -> c -> b("/b/two") while a is still being walked
        write(a.resolve("S.java"), """
                package a;
                @org.springframework.stereotype.Service
                public class S {
                  private final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                  public void a(String u) { b(u); c(); }
                  void c() { b("/b/two"); }
                  void b(String u) { if (u.isEmpty()) { a(u); } rt.getForObject("http://svc-b" + u, String.class); }
                }
                """);
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(b.resolve("Api.java"), """
                package b;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  @org.springframework.web.bind.annotation.GetMapping("/b/one") void one() { }
                  @org.springframework.web.bind.annotation.GetMapping("/b/two") void two() { }
                }
                """);
        List<JsonNode> g = map(root, root.resolve("none.yml"), root.resolve("out"));
        Set<String> to = new TreeSet<>();
        g.stream().filter(e -> e.get("from").asText().equals("svc-a GET /c")).forEach(e -> to.add(e.get("to").asText()));
        assertEquals(Set.of("svc-b GET /b/one", "svc-b GET /b/two"), to);
    }

    // --- helpers ---

    private static JsonNode edge(String from, String to) {
        return edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals(to))
                .findFirst().orElseThrow(() -> new AssertionError("no edge " + from + " -> " + to + " in " + targets(from)));
    }

    private static Set<String> targets(String from) {
        return targets(from, "");
    }

    private static Set<String> targets(String from, String prefix) {
        Set<String> out = new TreeSet<>();
        edges.stream().filter(e -> e.get("from").asText().equals(from)).map(e -> e.get("to").asText())
                .filter(t -> t.startsWith(prefix)).forEach(out::add);
        return out;
    }

    private static Set<String> names(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<JsonNode> map(Path root, Path res, Path out) throws Exception {
        List<String> args = new ArrayList<>(List.of("map", "--root", root.toString(), "--profile", "prod", "--out", out.toString()));
        if (Files.exists(res)) {
            args.addAll(List.of("--resolutions", res.toString()));
        }
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(e, true)), e.toString());
        JsonNode g = JSON.readTree(out.resolve("graph.json").toFile());
        List<JsonNode> list = new ArrayList<>();
        g.get("edges").forEach(list::add);
        return list;
    }

    private static void run(String command, Path root, String... more) {
        List<String> args = new ArrayList<>(List.of(command, "--root", root.toString(), "--profile", "prod"));
        args.addAll(List.of(more));
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(e, true)), e.toString());
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
