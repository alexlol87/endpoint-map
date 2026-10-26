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

    @Test
    void wrapperNamesTheOutermostLibraryClassAndKeepsTheTransport() {
        // syncTenant -> DeepChainCaller.threeLevels -> relay -> InternalRestTemplateClient.fetch: the service calls DeepChainCaller
        JsonNode deep = edge(SYNC, "shipping-service GET /shipments/{id}");
        assertEquals("DeepChainCaller", deep.get("client").asText());
        assertEquals("resttemplate", deep.get("transport").asText());
        assertEquals("restclient", edge(INVOICES, "tax-service POST /tax-api/tax/calculate").get("transport").asText());
        assertEquals("webclient", edge(RESTOCK, "inventory-service GET /inventory/{sku}").get("transport").asText());
        assertEquals("resttemplate", edge("order-service POST /api/orders/restock/{sku}", "inventory-service GET /inventory/{sku}")
                .get("transport").asText());
    }

    /** lib-gw: GatewayClient(base) over RestTemplate, Relay.fetch(gw, path), an auto-config default bean; svc-1..3 targets. */
    static Path gatewayWorkspace(Path root) throws Exception {
        Path lib = root.resolve("lib-gw/src/main/java/lib/gw");
        write(root.resolve("lib-gw/pom.xml"), "<project><artifactId>lib-gw</artifactId></project>\n");
        write(lib.resolve("GatewayClient.java"), """
                package lib.gw;
                public class GatewayClient {
                  private final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                  private final String base;
                  public GatewayClient(String base) { this.base = base; }
                  public String get(String path) { return rt.getForObject(base + path, String.class); }
                }
                """);
        write(lib.resolve("Relay.java"), """
                package lib.gw;
                public class Relay {
                  public static String fetch(GatewayClient gw, String path) { return gw.get(path); }
                }
                """);
        write(lib.resolve("GwAutoConfig.java"), """
                package lib.gw;
                @org.springframework.context.annotation.Configuration
                public class GwAutoConfig {
                  @org.springframework.context.annotation.Bean
                  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
                  GatewayClient defaultGateway() { return new GatewayClient("http://legacy-gw:8080"); }
                }
                """);
        // nested wrappers W1(W2(W3(url))) built only by library presets
        write(lib.resolve("W3.java"), """
                package lib.gw;
                public class W3 {
                  private final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                  private final String base;
                  public W3(String base) { this.base = base; }
                  public String get(String p) { return rt.getForObject(base + p, String.class); }
                }
                """);
        write(lib.resolve("W2.java"), "package lib.gw;\npublic class W2 { private final W3 w; public W2(W3 w) { this.w = w; }\n"
                + " public String get(String p) { return w.get(\"/items\" + p); } }\n");
        write(lib.resolve("W1.java"), "package lib.gw;\npublic class W1 { private final W2 w; public W1(W2 w) { this.w = w; }\n"
                + " public String get(String p) { return w.get(p); } }\n");
        write(lib.resolve("Presets.java"), "package lib.gw;\npublic class Presets {\n"
                + " public static W1 p1() { return new W1(new W2(new W3(\"http://svc-1:8080\"))); }\n"
                + " public static W1 p2() { return new W1(new W2(new W3(\"http://svc-2:8080\"))); }\n"
                + " public static W1 p3() { return new W1(new W2(new W3(\"http://svc-3:8080\"))); }\n}\n");
        for (int i = 1; i <= 3; i++) {
            Path t = root.resolve("svc-" + i + "/src/main/java/t" + i);
            write(t.resolve("App.java"), "package t" + i + ";\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
            write(t.resolve("Api.java"), "package t" + i + ";\n@org.springframework.web.bind.annotation.RestController\npublic class Api {\n"
                    + "  @org.springframework.web.bind.annotation.GetMapping(\"/items/{id}\") String item() { return \"\"; }\n}\n");
        }
        return root;
    }

    @Test
    void aWrapperPassedAsAnArgumentIsTheOneTheCallerBuilt(@TempDir Path root) throws Exception {
        Path s = gatewayWorkspace(root).resolve("svc-0/src/main/java/s0");
        write(s.resolve("App.java"), "package s0;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(s.resolve("Cfg.java"), """
                package s0;
                @org.springframework.context.annotation.Configuration
                public class Cfg {
                  @org.springframework.context.annotation.Bean lib.gw.GatewayClient gw() { return new lib.gw.GatewayClient("http://svc-1:8080"); }
                }
                """);
        write(s.resolve("Api.java"), """
                package s0;
                import lib.gw.*;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Api {
                  private final GatewayClient gw; private final GatewayClient a; private final GatewayClient b;
                  Api(GatewayClient gw) { this.gw = gw; this.a = new GatewayClient("http://svc-2:8080"); this.b = new GatewayClient("http://svc-3:8080"); }
                  @GetMapping("/a") String a() { return gw.get("/items/1"); }
                  @GetMapping("/b") String b() { return Relay.fetch(a, "/items/2"); }
                  @GetMapping("/c") String c() { return via(a); }
                  @GetMapping("/d") String d() { return via(b); }
                  @GetMapping("/e") String e() { return Relay.fetch(gw, "/items/5"); }
                  @GetMapping("/f") String f() { return h1(b); }
                  private String via(GatewayClient g) { return g.get("/items/3"); }
                  private String h1(GatewayClient g) { return h2(g); }
                  private String h2(GatewayClient g) { return h3(g); }
                  private String h3(GatewayClient g) { return Relay.fetch(g, "/items/6"); }
                }
                """);
        List<JsonNode> g = map(root, root.resolve("none.yml"), root.resolve("out"));
        assertEquals(Set.of("svc-1 GET /items/{id}"), targets(g, "svc-0 GET /a"));
        assertEquals(Set.of("svc-2 GET /items/{id}"), targets(g, "svc-0 GET /b"));
        assertEquals(Set.of("svc-2 GET /items/{id}"), targets(g, "svc-0 GET /c"));
        assertEquals(Set.of("svc-3 GET /items/{id}"), targets(g, "svc-0 GET /d"));
        assertEquals(Set.of("svc-1 GET /items/{id}"), targets(g, "svc-0 GET /e")); // the injected bean, passed on
        // passed through 4 calls: beyond propagation, the argument is unknown; only the service's own objects remain
        assertEquals(Set.of("svc-1 GET /items/{id}", "svc-2 GET /items/{id}", "svc-3 GET /items/{id}"), targets(g, "svc-0 GET /f"));
    }

    @Test
    void libraryCreationsCountOnlyThroughTheServicesOwnCode(@TempDir Path root) throws Exception {
        gatewayWorkspace(root);
        // svc-0: its own GatewayClient bean (the library default steps back) and a W1 bean from one preset
        Path s0 = root.resolve("svc-0/src/main/java/s0");
        write(s0.resolve("App.java"), "package s0;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(s0.resolve("Cfg.java"), """
                package s0;
                @org.springframework.context.annotation.Configuration
                public class Cfg {
                  @org.springframework.context.annotation.Bean lib.gw.GatewayClient gw() { return new lib.gw.GatewayClient("http://svc-1:8080"); }
                  @org.springframework.context.annotation.Bean lib.gw.W1 w1() { return lib.gw.Presets.p2(); }
                }
                """);
        String api = """
                package %s;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  private final lib.gw.GatewayClient gw; private final lib.gw.W1 w;
                  Api(lib.gw.GatewayClient gw, lib.gw.W1 w) { this.gw = gw; this.w = w; }
                  @org.springframework.web.bind.annotation.GetMapping("/g") String g() { return gw.get("/items/1"); }
                  @org.springframework.web.bind.annotation.GetMapping("/w") String w() { return w.get("/7"); }
                }
                """;
        write(s0.resolve("Api.java"), api.formatted("s0"));
        // svc-4: no GatewayClient bean of its own (a Holder bean that builds one inside is not one), so the library
        // default is the one injected
        Path s4 = root.resolve("svc-4/src/main/java/s4");
        write(s4.resolve("App.java"), "package s4;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(s4.resolve("Holder.java"), "package s4;\npublic class Holder { Holder(lib.gw.GatewayClient gw) { } }\n");
        write(s4.resolve("Cfg.java"), """
                package s4;
                @org.springframework.context.annotation.Configuration
                public class Cfg {
                  @org.springframework.context.annotation.Bean lib.gw.W1 w1() { return lib.gw.Presets.p3(); }
                  @org.springframework.context.annotation.Bean Holder holder() { return new Holder(new lib.gw.GatewayClient("http://svc-2:8080")); }
                }
                """);
        write(s4.resolve("Api.java"), api.formatted("s4"));
        List<JsonNode> g = map(root, root.resolve("none.yml"), root.resolve("out"));
        assertEquals(Set.of("svc-1 GET /items/{id}"), targets(g, "svc-0 GET /g"));
        assertEquals(Set.of("svc-2 GET /items/{id}"), targets(g, "svc-0 GET /w"));
        assertEquals(Set.of("external:http://legacy-gw:8080 GET /items/1"), targets(g, "svc-4 GET /g"));
        assertEquals(Set.of("svc-3 GET /items/{id}"), targets(g, "svc-4 GET /w"));
        JsonNode w = g.stream().filter(e -> e.get("from").asText().equals("svc-0 GET /w")).findFirst().orElseThrow();
        assertEquals("W1", w.get("client").asText()); // the class the service calls, not the innermost W3
        assertEquals("resttemplate", w.get("transport").asText());
    }

    @Test
    @org.junit.jupiter.api.Timeout(120)
    void aLargeRecursiveClusterIsBoundedAndNeverPairsAUrlWrongly(@TempDir Path root) throws Exception {
        int k = 10;
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Api.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  private final G g;
                  Api(G g) { this.g = g; }
                  @org.springframework.web.bind.annotation.GetMapping("/e") void e() { g.m0("/x"); }
                }
                """);
        // every method calls every other with its own path suffix: ~600 argument combinations
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < k; i++) {
            body.append("  void m").append(i).append("(String p) { if (p.isEmpty()) { ");
            for (int j = 0; j < k; j++) {
                if (j != i) {
                    body.append("m").append(j).append("(p + \"/").append(i).append(j).append("\"); ");
                }
            }
            body.append("} rt.getForObject(\"http://svc-b\" + p, String.class); }\n");
        }
        write(a.resolve("G.java"), "package a;\n@org.springframework.stereotype.Service\npublic class G {\n"
                + "  final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();\n" + body + "}\n");
        run("discover", root, "--resolutions", root.resolve("conf/resolutions.yml").toString());
        // the paths it did not follow are never dropped silently
        assertTrue(Files.readString(root.resolve("conf/discover-report.md"))
                .contains("tracing svc-a GET /e: the recursive cluster at G.m0 has too many paths"));
        List<JsonNode> g = map(root, root.resolve("none.yml"), root.resolve("out"));
        List<JsonNode> out = g.stream().filter(e -> e.get("from").asText().equals("svc-a GET /e")).toList();
        Set<String> to = targets(g, "svc-a GET /e");
        assertEquals(k, to.size(), to.toString()); // one edge per client call site, not one per path
        assertTrue(to.contains("external:http://svc-b GET /x"), to.toString());
        assertTrue(to.stream().anyMatch(t -> t.endsWith(" GET {*}")), to.toString()); // too many paths: a callSites entry
        // a known URL is one its call site really gets: a chain /x/0a/ab/b.. ending in that method's number
        for (JsonNode e : out) {
            String t = e.get("to").asText();
            if (t.startsWith("external:http://svc-b GET /x")) {
                String[] seg = t.substring("external:http://svc-b GET ".length()).split("/");
                char last = '0';
                for (int j = 2; j < seg.length; j++) {
                    assertEquals(last, seg[j].charAt(0), t);
                    last = seg[j].charAt(1);
                }
                assertEquals(last - '0' + 5, e.get("line").asInt(), t);
            }
        }
    }

    /** A chain of n trivial helper methods: many walks, no recursion. */
    private static String pad(int n) {
        StringBuilder pad = new StringBuilder("package a;\n@org.springframework.stereotype.Component\npublic class Pad {\n");
        for (int i = 0; i < n; i++) {
            pad.append("  int p").append(i).append("(int x) { return ").append(i + 1 < n ? "p" + (i + 1) + "(x + 1)" : "x").append("; }\n");
        }
        return pad.append("}\n").toString();
    }

    @Test
    void aBigHandlerWithoutRecursionKeepsEveryExactEdge(@TempDir Path root) throws Exception {
        write(root.resolve("lib-gw/pom.xml"), "<project><artifactId>lib-gw</artifactId></project>\n");
        write(root.resolve("lib-gw/src/main/java/lib/gw/GatewayClient.java"), """
                package lib.gw;
                public class GatewayClient {
                  private final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                  private final String base;
                  public GatewayClient(String base) { this.base = base; }
                  public String get(String path) { return rt.getForObject(base + path, String.class); }
                }
                """);
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Cfg.java"), """
                package a;
                @org.springframework.context.annotation.Configuration
                public class Cfg {
                  @org.springframework.context.annotation.Bean lib.gw.GatewayClient gw() { return new lib.gw.GatewayClient("http://svc-b"); }
                }
                """);
        write(a.resolve("Pad.java"), pad(120));
        write(a.resolve("Api.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  private final Pad pad; private final lib.gw.GatewayClient gw;
                  Api(Pad pad, lib.gw.GatewayClient gw) { this.pad = pad; this.gw = gw; }
                  @org.springframework.web.bind.annotation.GetMapping("/agg") String agg() {
                    pad.p0(0);
                    return gw.get("/i1") + gw.get("/i2") + gw.get("/i3") + gw.get("/i4") + gw.get("/i5");
                  }
                }
                """);
        Set<String> to = targets(map(root, root.resolve("none.yml"), root.resolve("out")), "svc-a GET /agg");
        assertEquals(Set.of("external:http://svc-b GET /i1", "external:http://svc-b GET /i2", "external:http://svc-b GET /i3",
                "external:http://svc-b GET /i4", "external:http://svc-b GET /i5"), to);
    }

    @Test
    void aSmallClusterBehindABigHandlerKeepsEveryPathWhateverTheOtherEndpoints(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Pad.java"), pad(120));
        write(a.resolve("Api.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  private final G g; private final Pad pad;
                  Api(G g, Pad pad) { this.g = g; this.pad = pad; }
                  @org.springframework.web.bind.annotation.GetMapping("/e0") void e0() { pad.p0(0); g.m0("/x"); }
                  @org.springframework.web.bind.annotation.GetMapping("/e1") void e1() { pad.p0(0); g.m1("/x"); }
                  @org.springframework.web.bind.annotation.GetMapping("/e2") void e2() { g.m2("/x"); }
                }
                """);
        write(a.resolve("G.java"), """
                package a;
                @org.springframework.stereotype.Service
                public class G {
                  final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                  void m0(String p) { if (p.isEmpty()) { m1(p + "/01"); m2(p + "/02"); } rt.getForObject("http://svc-b" + p, String.class); }
                  void m1(String p) { if (p.isEmpty()) { m0(p + "/10"); m2(p + "/12"); } rt.getForObject("http://svc-b" + p, String.class); }
                  void m2(String p) { if (p.isEmpty()) { m0(p + "/20"); m1(p + "/21"); } rt.getForObject("http://svc-b" + p, String.class); }
                }
                """);
        List<JsonNode> before = map(root, root.resolve("none.yml"), root.resolve("out1"));
        assertEquals(Set.of("external:http://svc-b GET /x", "external:http://svc-b GET /x/01", "external:http://svc-b GET /x/01/12",
                "external:http://svc-b GET /x/02", "external:http://svc-b GET /x/02/21"), targets(before, "svc-a GET /e0"));
        // an unrelated endpoint traced first (it walks the same helpers) changes nothing for the others
        write(a.resolve("Aa.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Aa {
                  private final Pad pad; private final G g;
                  Aa(Pad pad, G g) { this.pad = pad; this.g = g; }
                  @org.springframework.web.bind.annotation.GetMapping("/aa") int aa() { g.m2("/y"); return pad.p60(0); }
                }
                """);
        List<JsonNode> after = map(root, root.resolve("none.yml"), root.resolve("out2"));
        for (String e : List.of("svc-a GET /e0", "svc-a GET /e1", "svc-a GET /e2")) {
            assertEquals(before.stream().filter(x -> x.get("from").asText().equals(e)).toList(),
                    after.stream().filter(x -> x.get("from").asText().equals(e)).toList(), e);
        }
    }

    private static Set<String> targets(List<JsonNode> g, String from) {
        Set<String> out = new TreeSet<>();
        g.stream().filter(e -> e.get("from").asText().equals(from)).forEach(e -> out.add(e.get("to").asText()));
        return out;
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
