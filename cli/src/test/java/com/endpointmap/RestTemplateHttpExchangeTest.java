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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: RestTemplate calls and Spring HTTP interface (@HttpExchange) clients become edges (issue #6). */
class RestTemplateHttpExchangeTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final Path ENV = Path.of("..", "test-environment");
    static final String RESTOCK = "order-service POST /api/orders/restock/{sku}";
    static final String REPORT = "report-service GET /reports/{type}";

    @TempDir
    static Path dir;
    static List<JsonNode> edges;
    static String resolutions;

    @BeforeAll
    static void discoverAndMap() throws Exception {
        Path res = dir.resolve("resolutions.yml");
        run("discover", ENV, "--resolutions", res.toString());
        resolutions = Files.readString(res);
        edges = map(ENV, res, dir.resolve("out"));
    }

    @Test
    void restTemplateVerbComesFromTheMethodNameOrTheHttpMethodArgument() {
        // getForEntity / patchForObject on a RestTemplateBuilder.rootUri(${inventory.base-url}) bean picked by @Qualifier
        JsonNode get = edge(RESTOCK, "inventory-service GET /inventory/{sku}");
        assertEquals("resttemplate", get.get("client").asText());
        assertEquals(List.of("RestockService.restock", "RestTemplate.getForEntity"), strings(get.get("via")));
        assertEquals("order-service/src/main/java/com/acme/order/service/RestockService.java", get.get("file").asText());
        assertEquals(24, get.get("line").asInt());
        edge(RESTOCK, "inventory-service PATCH /inventory/{sku}/quantity");
        // getForObject 5 hops down, postForEntity with {id}/{amount} query template vars, postForObject
        assertEquals(List.of("CheckoutHandlerImpl.handle", "CheckoutService.checkout", "OrderPricingService.finalTotal",
                "OrderTaxService.rateFor", "TaxRestUtils.fetchRate", "RestTemplate.getForObject"),
                strings(edge("order-service POST /api/orders/{id}/checkout", "tax-service GET /tax-api/tax/rates/{region}").get("via")));
        assertEquals("resttemplate", edge("order-service POST /api/orders", "unresolved:order-service:${PAYMENT_SERVICE_URL} POST /payments/charge")
                .get("client").asText());
        edge("order-service POST /api/orders/{id}/cancel", "unresolved:order-service:${PAYMENT_SERVICE_URL} POST /payments/{*}/refund");
        // exchange(url, HttpMethod.POST, ..) and execute(url, HttpMethod.POST, ..) on an injected @Bean RestTemplate
        assertEquals(List.of("PaymentGateway.charge", "RestTemplate.exchange"),
                strings(edge("payment-service POST /payments/charge", "unresolved:payment-service:${PAYMENT_HOST} POST /v1/charges").get("via")));
        edge("payment-service POST /payments/{id}/refund", "unresolved:payment-service:${PAYMENT_HOST} POST /v1/refunds");
        edge("payment-service POST /payments/charge", "external:https://fraud.example-vendor.com GET /v1/score");
        edge("notification-service POST /notifications/{userId}", "external:https://fcm.googleapis.com POST /v1/projects/acme/messages:send");
        // library wrapper: put(url) and exchange(HttpMethod.PUT passed in by the caller, url)
        assertEquals(List.of("ReportService.export", "InternalRestTemplateClient.exchange", "RestTemplate.exchange"),
                strings(edge("report-service POST /reports/export", "unresolved:report-service:${REPORT_EXPORT_URL} PUT /{*}/x").get("via")));
        // UrlTemplates.join(taxUrl, "tax", "rates", s): the StringBuilder loop over the varargs is unrolled (#7);
        // tax-service has no PUT endpoint, so it stays unresolved, now with the right path
        edge("billing-service POST /billing/tenants/{tenant}/sync", "unresolved:tax-service PUT /tax-api/tax/rates/{*}");
    }

    @Test
    void reportServiceTernaryUrlGivesOneEdgePerBranch() {
        // String source = "daily".equals(type) ? ordersUrl + "/orders" : ordersUrl + "/orders/search"; http.fetch(source)
        assertEquals(Set.of("order-service GET /api/orders", "order-service GET /api/orders/search", "order-service GET /api/orders/{id}"),
                targetsOf(REPORT, "order-service")); // its JDBC tables (#11) are db edges
        assertEquals(List.of("ReportService.build", "InternalRestTemplateClient.fetch", "RestTemplate.getForObject"),
                strings(edge(REPORT, "order-service GET /api/orders/search").get("via")));
    }

    @Test
    void httpExchangeClientsTakeTheirBaseFromTheProxyFactoryBean() {
        // RestClientAdapter over a RestClient bean + class-level @HttpExchange(url = "/billing")
        JsonNode billing = edge("shipping-service POST /shipments", "billing-service POST /billing/invoices");
        assertEquals("httpexchange", billing.get("client").asText());
        assertEquals(List.of("BillingExchangeClient.createInvoice"), strings(billing.get("via")));
        // RestTemplateAdapter over a RestTemplate with a DefaultUriBuilderFactory root; the interface is a library's
        // that tax-service's controller implements: still an outbound call, and still tax-service's inbound endpoint
        JsonNode tax = edge(RESTOCK, "tax-service GET /tax-api/tax/rates/{region}");
        assertEquals("httpexchange", tax.get("client").asText());
        assertEquals(List.of("RestockService.restock", "TaxRatesApi.rate"), strings(tax.get("via")));
        // library interface + library factory ExchangeClients.create(props.shippingUrl(), ..): the env var is asked
        edge("warehouse-service POST /warehouse/dispatch/{sku}", "unresolved:warehouse-service:${SHIPPING_PROD_URL} POST /shipments");
    }

    @Test
    void unresolvableRestTemplateAndExchangeUrlsGoToResolutions() throws Exception {
        JsonNode y = YAML.readTree(resolutions);
        assertTrue(y.get("payment-service").get("properties").has("${PAYMENT_HOST}"), resolutions);
        assertTrue(y.get("report-service").get("properties").has("${REPORT_EXPORT_URL}"), resolutions);
        assertTrue(y.get("report-service").get("callSites").has("InternalRestTemplateClient.send:26"), resolutions);
        assertTrue(y.get("warehouse-service").get("properties").has("${SHIPPING_PROD_URL}"), resolutions);
        // four levels of parameter passing: beyond constant propagation
        assertTrue(y.get("billing-service").get("callSites").has("InternalRestTemplateClient.fetch:18"), resolutions);
        assertTrue(resolutions.contains("# hint: url = \"${PAYMENT_HOST}/v1/charges\""), resolutions);

        Path res = dir.resolve("edited.yml");
        Files.writeString(res, resolutions.replace("\"${SHIPPING_PROD_URL}\": \"\"", "\"${SHIPPING_PROD_URL}\": service:shipping-service"));
        assertTrue(has(map(ENV, res, dir.resolve("out-edited")), "warehouse-service POST /warehouse/dispatch/{sku}",
                "shipping-service POST /shipments"));
    }

    @Test
    void webClientAdapterProxiesInlineAndThroughFactoryBeans(@TempDir Path root) throws Exception {
        // recommendation-service and loyalty-service call their proxies from functional routes (#8): copied here
        // with an annotated controller in front
        for (String repo : List.of("lib-reactive-clients", "loyalty-service", "recommendation-service", "cart-service",
                "user-profile-service", "catalog-service")) {
            copy(ENV.resolve(repo).resolve("src"), root.resolve(repo).resolve("src"));
        }
        write(root.resolve("loyalty-service/src/main/java/com/acme/loyalty/SimulateController.java"), """
                package com.acme.loyalty;
                @org.springframework.web.bind.annotation.RestController
                public class SimulateController {
                  private final LoyaltyService service;
                  SimulateController(LoyaltyService service) { this.service = service; }
                  @org.springframework.web.bind.annotation.PostMapping("/t/{userId}") Object simulate(String userId) { return service.simulate(userId); }
                }
                """);
        write(root.resolve("recommendation-service/src/main/java/com/acme/recommendation/RecsController.java"), """
                package com.acme.recommendation;
                @org.springframework.web.bind.annotation.RestController
                public class RecsController {
                  private final RecommendationHandler handler;
                  RecsController(RecommendationHandler handler) { this.handler = handler; }
                  @org.springframework.web.bind.annotation.GetMapping("/t/{userId}") Object recs() { return handler.forUser(null); }
                }
                """);
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        List<JsonNode> g = map(root, res, root.resolve("out"));
        // cartExchange(HttpServiceProxyFactory cartProxyFactory) -> builderFor(WebClientAdapter.create(cartWebClient bean))
        assertTrue(has(g, "loyalty-service POST /t/{userId}", "cart-service GET /carts/{userId}/points-preview"), g.toString());
        // inline WebClient + WebClientAdapter; library factory with ${CATALOG_URL} unset
        assertTrue(has(g, "recommendation-service GET /t/{userId}", "user-profile-service GET /profile-api/profiles/{userId}"), g.toString());
        assertTrue(has(g, "recommendation-service GET /t/{userId}", "unresolved:recommendation-service:${CATALOG_URL} GET /catalog/products"),
                g.toString());
        assertTrue(YAML.readTree(res.toFile()).get("recommendation-service").get("properties").has("${CATALOG_URL}"));
    }

    @Test
    void builderUriHandlerDeleteVerbArgumentsAndAControllerImplementingTheInterface(@TempDir Path root) throws Exception {
        write(root.resolve("shared/src/main/java/s/ItemsApi.java"), """
                package s;
                import org.springframework.web.service.annotation.*;
                @HttpExchange("/b")
                public interface ItemsApi {
                  @GetExchange("/items/{id}") String get(@org.springframework.web.bind.annotation.PathVariable String id);
                }
                """);
        write(root.resolve("shared/src/main/java/s/AbsApi.java"), """
                package s;
                import org.springframework.web.service.annotation.*;
                @HttpExchange(url = "http://svc-b/b")
                public interface AbsApi {
                  @HttpExchange(method = "GET", url = "/abs") String abs();
                }
                """);
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Config.java"), """
                package a;
                import org.springframework.boot.restclient.RestTemplateBuilder;
                import org.springframework.context.annotation.Bean;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.support.*;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;
                import org.springframework.web.util.DefaultUriBuilderFactory;
                @org.springframework.context.annotation.Configuration
                public class Config {
                  @Bean s.ItemsApi items(RestTemplateBuilder b) {
                    return HttpServiceProxyFactory.builderFor(RestTemplateAdapter.create(
                        b.uriTemplateHandler(new DefaultUriBuilderFactory("http://svc-b")).build())).build().createClient(s.ItemsApi.class);
                  }
                  @Bean s.AbsApi abs() {
                    return HttpServiceProxyFactory.builder().exchangeAdapter(RestClientAdapter.create(RestClient.create())).build()
                        .createClient(s.AbsApi.class);
                  }
                }
                """);
        write(a.resolve("Api.java"), """
                package a;
                import org.springframework.http.HttpMethod;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.client.RestTemplate;
                @RestController
                public class Api {
                  private final s.ItemsApi items;
                  private final s.AbsApi abs;
                  private final RestTemplate rt = new RestTemplate();
                  private final Wrapper unknown;
                  Api(s.ItemsApi items, s.AbsApi abs) { this.items = items; this.abs = abs; this.unknown = new Wrapper(rt); }
                  @GetMapping("/call") String call() { return items.get("1"); }
                  @GetMapping("/abs") String abs() { return abs.abs(); }
                  @PatchMapping("/send/{id}") void send(String id) { send(HttpMethod.PATCH, "http://svc-b/b/items/{id}", id); }
                  @DeleteMapping("/a/things/{id}") void del(String id) { rt.delete("http://svc-b/b/items/{id}", id); }
                  @GetMapping("/relative") void relative() { unknown.go(); }
                  void send(HttpMethod m, String url, String id) { rt.exchange(url, m, null, Void.class, id); }
                }
                """);
        write(a.resolve("Wrapper.java"), """
                package a;
                public class Wrapper {
                  private final org.springframework.web.client.RestTemplate t;
                  public Wrapper(org.springframework.web.client.RestTemplate t) { this.t = t; }
                  void go() { t.getForObject("/b/items/1", String.class); }
                }
                """);
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        // the controller implements ItemsApi: injected into svc-b's own Local, the call is local and walked into
        write(b.resolve("ItemsController.java"), """
                package b;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class ItemsController implements s.ItemsApi {
                  private final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                  @Override public String get(String id) { rt.delete("http://svc-a/a/things/{id}", id); return id; }
                  @PatchMapping("/b/items/{id}") void patch() { }
                  @DeleteMapping("/b/items/{id}") void delete() { }
                  @GetMapping("/b/abs") void abs() { }
                }
                """);
        write(b.resolve("Local.java"), """
                package b;
                @org.springframework.web.bind.annotation.RestController
                public class Local {
                  private final s.ItemsApi items;
                  Local(s.ItemsApi items) { this.items = items; }
                  @org.springframework.web.bind.annotation.GetMapping("/local") String local() { return items.get("1"); }
                }
                """);
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        List<JsonNode> g = map(root, res, root.resolve("out"));
        // RestTemplateBuilder.uriTemplateHandler(new DefaultUriBuilderFactory(..)) under RestTemplateAdapter
        assertTrue(has(g, "svc-a GET /call", "svc-b GET /b/items/{id}"), g.toString());
        assertTrue(g.stream().anyMatch(e -> e.get("from").asText().equals("svc-a GET /call") && e.get("client").asText().equals("httpexchange")));
        // absolute class-level @HttpExchange url, method attribute, builder().exchangeAdapter(..)
        assertTrue(has(g, "svc-a GET /abs", "svc-b GET /b/abs"), g.toString());
        // exchange with the HttpMethod passed by the caller; delete; {id} stays a template variable
        assertTrue(has(g, "svc-a PATCH /send/{id}", "svc-b PATCH /b/items/{id}"), g.toString());
        assertTrue(has(g, "svc-a DELETE /a/things/{id}", "svc-b DELETE /b/items/{id}"), g.toString());
        // the controller itself: svc-b's own call on ItemsApi walks into ItemsController.get, it is not an HTTP call
        assertTrue(has(g, "svc-b GET /local", "svc-a DELETE /a/things/{id}"), g.toString());
        assertFalse(has(g, "svc-b GET /local", "svc-b GET /b/items/{id}"), g.toString());
        // a relative URL on a RestTemplate whose root nobody can tell (built by hand): a call site to ask about
        assertTrue(has(g, "svc-a GET /relative", "unresolved:svc-a:Wrapper.go:5 GET /b/items/1"), g.toString());
        assertTrue(YAML.readTree(res.toFile()).get("svc-a").get("callSites").has("Wrapper.go:5"));
    }

    @Test
    void argumentCombinationsAcrossThreeLevelsAreCappedPerCallSite(@TempDir Path root) throws Exception {
        // every level passes 3 String args, each a ternary of 2 values: hundreds of URL combinations at L3's call
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Api.java"), """
                package a;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Api {
                  private final L1 l;
                  Api(L1 l) { this.l = l; }
                  @GetMapping("/x/{t}") void x(@PathVariable String t) {
                    l.go(t.isEmpty() ? "/a" : "/b", t.isBlank() ? "/c" : "/d", t.equals("q") ? "/e" : "/f");
                  }
                }
                """);
        for (int i = 1; i <= 2; i++) {
            write(a.resolve("L" + i + ".java"), ("package a;\n@org.springframework.stereotype.Service\npublic class L%d {\n"
                    + "  private final L%d nx; L%d(L%d nx) { this.nx = nx; } boolean k = Math.random() > .5;\n"
                    + "  public void go(String p, String q, String r) { nx.go(k ? p + \"/%da\" : p + \"/%db\", k ? q : q + \"/%dc\","
                    + " k ? r + \"/%dd\" : r); nx.go(p, q, r); }\n}\n").formatted(i, i + 1, i, i + 1, i, i, i, i));
        }
        write(a.resolve("L3.java"), """
                package a;
                @org.springframework.stereotype.Service
                public class L3 {
                  private final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                  boolean k = Math.random() > .5;
                  public void go(String p, String q, String r) { rt.getForObject("http://svc-b" + (k ? "/items" : "/other") + p + q + r, String.class); }
                }
                """);
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(b.resolve("Items.java"), """
                package b;
                @org.springframework.web.bind.annotation.RestController
                public class Items { @org.springframework.web.bind.annotation.GetMapping("/items/{p}/{q}/{r}") void y() { } }
                """);
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        List<JsonNode> g = map(root, res, root.resolve("out"));
        List<JsonNode> from = g.stream().filter(e -> e.get("from").asText().equals("svc-a GET /x/{t}")).toList();
        assertTrue(from.size() <= 4, from.toString());
        assertTrue(has(g, "svc-a GET /x/{t}", "unresolved:svc-a:L3.go:6 GET {*}"), from.toString());
        assertFalse(from.stream().anyMatch(e -> e.get("to").asText().matches(".*\\S\\{\\*}.*")), "no partly unknown edge: " + from);
        assertTrue(YAML.readTree(res.toFile()).get("svc-a").get("callSites").has("L3.go:6"));
        String yml = Files.readString(res);
        assertTrue(yml.contains("# hint: url = one of ") && yml.contains("combinations, e.g. \"http://svc-b/"), yml);
    }

    @Test
    void restOperationsSubclassesAndQualifiedComponentBeans(@TempDir Path root) throws Exception {
        write(root.resolve("shared/src/main/java/s/ItemsApi.java"), """
                package s;
                import org.springframework.web.service.annotation.*;
                public interface ItemsApi {
                  @GetExchange("/items/{id}") String get(@org.springframework.web.bind.annotation.PathVariable String id);
                }
                """);
        write(root.resolve("shared/src/main/java/s/TypeVerbApi.java"), """
                package s;
                import org.springframework.web.service.annotation.*;
                @HttpExchange(url = "/tv", method = "POST")
                public interface TypeVerbApi { @HttpExchange("/do") String go(); }
                """);
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("AcmeTemplate.java"), "package a;\npublic class AcmeTemplate extends org.springframework.web.client.RestTemplate {}\n");
        write(a.resolve("Config.java"), """
                package a;
                import org.springframework.context.annotation.Bean;
                import org.springframework.web.client.*;
                import org.springframework.web.client.support.RestClientAdapter;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;
                @org.springframework.context.annotation.Configuration
                public class Config {
                  @Bean RestOperations ops() { return new RestTemplate(); }
                  @Bean s.TypeVerbApi tv() {
                    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(RestClient.create("http://svc-b"))).build()
                        .createClient(s.TypeVerbApi.class);
                  }
                }
                """);
        write(a.resolve("Api.java"), """
                package a;
                import org.springframework.http.*;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.security.oauth2.client.OAuth2RestTemplate;
                @RestController
                public class Api {
                  private final org.springframework.web.client.RestOperations ops;
                  private final OAuth2RestTemplate oauth;
                  private final AcmeTemplate acme = new AcmeTemplate();
                  private final s.TypeVerbApi tv;
                  Api(org.springframework.web.client.RestOperations ops, OAuth2RestTemplate oauth, s.TypeVerbApi tv) { this.ops = ops; this.oauth = oauth; this.tv = tv; }
                  @GetMapping("/ops") String ops() { return ops.getForObject("http://svc-b/items/1", String.class); }
                  @GetMapping("/oauth") String oauth() { return oauth.getForObject("/items/2", String.class); }
                  @GetMapping("/acme") void acme() { acme.delete("http://svc-b/items/3"); }
                  @GetMapping("/re") void re() { acme.exchange(RequestEntity.post(java.net.URI.create("http://svc-b/items")).build(), String.class); }
                  @GetMapping("/tern/{f}") void tern(boolean f) { acme.exchange("http://svc-b/items", f ? HttpMethod.POST : HttpMethod.GET, null, String.class); }
                  @GetMapping("/tv") String tv() { return tv.go(); }
                }
                """);
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(b.resolve("Config.java"), """
                package b;
                import org.springframework.context.annotation.Bean;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.support.RestClientAdapter;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;
                @org.springframework.context.annotation.Configuration
                public class Config {
                  @Bean @org.springframework.beans.factory.annotation.Qualifier("peer") s.ItemsApi peerItems() {
                    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(RestClient.create("http://svc-a"))).build()
                        .createClient(s.ItemsApi.class);
                  }
                }
                """);
        write(b.resolve("ItemsController.java"), """
                package b;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class ItemsController implements s.ItemsApi {
                  @Override public String get(String id) { return id; }
                  @PostMapping("/items") void post() { }
                  @GetMapping("/items") void list() { }
                  @PostMapping("/tv/do") void tv() { }
                  @DeleteMapping("/items/{id}") void delete() { }
                }
                """);
        write(b.resolve("Peer.java"), """
                package b;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Peer {
                  private final s.ItemsApi peer;
                  private final s.ItemsApi local;
                  private final s.ItemsApi nope;
                  Peer(@Qualifier("peer") s.ItemsApi peer, @Qualifier("itemsController") s.ItemsApi local, @Qualifier("nope") s.ItemsApi nope) {
                    this.peer = peer; this.local = local; this.nope = nope;
                  }
                  @GetMapping("/peer/{id}") String peer(String id) { return peer.get(id); }
                  @GetMapping("/local/{id}") String local(String id) { return local.get(id); }
                  @GetMapping("/nope/{id}") String nope(String id) { return nope.get(id); }
                }
                """);
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        List<JsonNode> g = map(root, res, root.resolve("out"));
        // RestOperations bean, OAuth2RestTemplate (outside the workspace), a workspace subclass of RestTemplate
        assertTrue(has(g, "svc-a GET /ops", "svc-b GET /items/{id}"), g.toString());
        assertTrue(has(g, "svc-a GET /acme", "svc-b DELETE /items/{id}"), g.toString());
        assertTrue(g.stream().anyMatch(e -> e.get("from").asText().equals("svc-a GET /oauth")
                && e.get("to").asText().startsWith("unresolved:svc-a:Api.oauth:")), g.toString());
        // exchange(RequestEntity, type): a call site, verb read from RequestEntity.post
        assertTrue(g.stream().anyMatch(e -> e.get("from").asText().equals("svc-a GET /re")
                && e.get("to").asText().matches("unresolved:svc-a:Api.re:\\d+ POST \\{\\*}")), g.toString());
        // a verb chosen by a ternary: one edge per branch
        assertTrue(has(g, "svc-a GET /tern/{f}", "svc-b POST /items"), g.toString());
        assertTrue(has(g, "svc-a GET /tern/{f}", "svc-b GET /items"), g.toString());
        // @HttpExchange(method = "POST") on the interface
        assertTrue(has(g, "svc-a GET /tv", "svc-b POST /tv/do"), g.toString());
        // qualified: the proxy bean is a call to svc-a, the qualified local controller and an unmatched qualifier are not
        assertTrue(has(g, "svc-b GET /peer/{id}", "unresolved:svc-a GET /items/{id}"), g.toString());
        assertFalse(g.stream().anyMatch(e -> e.get("from").asText().equals("svc-b GET /local/{id}")), g.toString());
        assertFalse(g.stream().anyMatch(e -> e.get("from").asText().equals("svc-b GET /nope/{id}")
                && e.get("to").asText().startsWith("svc-a")), g.toString());
    }

    @Test
    void separateCallsThroughAHelperKeepTheirEdgesAndClientTypesStayInTheirService(@TempDir Path root) throws Exception {
        write(root.resolve("shared/src/main/java/l/ItemsApi.java"), """
                package l;
                @org.springframework.web.service.annotation.HttpExchange("/items")
                public interface ItemsApi {
                  @org.springframework.web.service.annotation.GetExchange("/{id}") String get(@org.springframework.web.bind.annotation.PathVariable String id);
                }
                """);
        // a stub in the library: not component-scanned by the services, so not a local implementation for svc-a
        write(root.resolve("shared/src/main/java/l/ItemsStub.java"), """
                package l;
                @org.springframework.stereotype.Component
                public class ItemsStub implements ItemsApi { public String get(String id) { return id; } }
                """);
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Config.java"), """
                package a;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.support.RestClientAdapter;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;
                @org.springframework.context.annotation.Configuration
                public class Config {
                  @org.springframework.context.annotation.Bean l.ItemsApi itemsApi() {
                    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(RestClient.create("http://svc-b"))).build()
                        .createClient(l.ItemsApi.class);
                  }
                }
                """);
        write(a.resolve("Http.java"), """
                package a;
                @org.springframework.stereotype.Component
                public class Http {
                  private final org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                  public String get(String path) { return rt.getForObject("http://svc-b" + path, String.class); }
                }
                """);
        write(a.resolve("Agg.java"), """
                package a;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Agg {
                  private final Http http; private final l.ItemsApi items;
                  Agg(Http http, l.ItemsApi items) { this.http = http; this.items = items; }
                  @GetMapping("/six") void six() {
                    http.get("/e1"); http.get("/e2"); http.get("/e3");
                    http.get("/e4"); http.get("/e5"); http.get("/e6");
                  }
                  @GetMapping("/hx") String hx() { return items.get("1"); }
                }
                """);
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(b.resolve("E.java"), """
                package b;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class E {
                  @GetMapping("/e1") void e1() { } @GetMapping("/e2") void e2() { } @GetMapping("/e3") void e3() { }
                  @GetMapping("/e4") void e4() { } @GetMapping("/e5") void e5() { } @GetMapping("/e6") void e6() { }
                  @GetMapping("/items/{id}") void item() { }
                }
                """);
        // an ApiClient unrelated to svc-c's RestTemplate subclass of the same name
        write(b.resolve("ApiClient.java"), """
                package b;
                @org.springframework.stereotype.Component
                public class ApiClient { public void delete(String id) { } public String getForObject(String k, Class<?> c) { return k; } }
                """);
        write(b.resolve("UsesApiClient.java"), """
                package b;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class UsesApiClient {
                  private final ApiClient client;
                  UsesApiClient(ApiClient c) { this.client = c; }
                  @GetMapping("/cache/{k}") String k(@PathVariable String k) { client.delete(k); return client.getForObject("http://svc-b/e1", String.class); }
                }
                """);
        Path c = root.resolve("svc-c/src/main/java/c");
        write(c.resolve("App.java"), "package c;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(c.resolve("ApiClient.java"), "package c;\npublic class ApiClient extends org.springframework.web.client.RestTemplate {}\n");
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        List<JsonNode> g = map(root, res, root.resolve("out"));
        for (int i = 1; i <= 6; i++) {
            assertTrue(has(g, "svc-a GET /six", "svc-b GET /e" + i), g.toString());
        }
        assertEquals(6, g.stream().filter(e -> e.get("from").asText().equals("svc-a GET /six")).count(), g.toString());
        assertTrue(has(g, "svc-a GET /hx", "svc-b GET /items/{id}"), g.toString());
        assertFalse(g.stream().anyMatch(e -> e.get("from").asText().equals("svc-b GET /cache/{k}")), g.toString());
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

    private static List<JsonNode> map(Path root, Path res, Path out) throws Exception {
        List<String> args = new ArrayList<>(List.of("map", "--root", root.toString(), "--profile", "prod", "--out", out.toString()));
        if (Files.exists(res)) {
            args.addAll(List.of("--resolutions", res.toString()));
        }
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(e, true)), e.toString());
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

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                write(to.resolve(from.relativize(f).toString()), Files.readString(f));
            }
        }
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
