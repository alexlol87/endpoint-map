package com.endpointmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: Feign calls traced from handlers become edges in graph.json (issue #3). */
class FeignTraceTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String CREATE = "order-service POST /api/orders";
    static final String CHECKOUT = "order-service POST /api/orders/{id}/checkout";

    @TempDir
    static Path out;
    static Graph env;

    @BeforeAll
    static void runMap() throws Exception {
        env = map(Path.of("..", "test-environment"), "prod", out);
    }

    @Test
    void feignMethodsGiveVerbAndPathIncludingClientPathPrefix() {
        // @FeignClient(path = "/inventory") + @GetMapping/@PutMapping/@DeleteMapping/@RequestMapping(method = PATCH)
        assertEquals(Set.of("inventory-service GET /inventory/{sku}", "inventory-service PUT /inventory/{sku}/reserve",
                        "inventory-service PATCH /inventory/{sku}/quantity", "inventory-service DELETE /inventory/{sku}/reservation"),
                env.targetsOf(CREATE, "inventory-service "));
    }

    @Test
    void edgeCarriesFromToKindClientViaFileLine() {
        JsonNode e = env.edge(CREATE, "inventory-service PUT /inventory/{sku}/reserve");
        assertEquals("http", e.get("kind").asText());
        assertEquals("feign", e.get("client").asText());
        assertEquals(List.of("OrderService.create", "InventoryFeignClient.reserve"), strings(e.get("via")));
        assertEquals("order-service/src/main/java/com/acme/order/service/OrderService.java", e.get("file").asText());
        assertEquals(56, e.get("line").asInt());
        // a method reference (skus().forEach(inventory::release)) is a call too
        assertEquals(List.of("OrderService.create", "InventoryFeignClient.release"),
                strings(env.edge(CREATE, "inventory-service DELETE /inventory/{sku}/reservation").get("via")));
    }

    @Test
    void literalUrlWithContextPathAndPropertyUrlFromProfileFile() {
        // url = "http://localhost:8082/customers-api" -> customer-service by port, its context path kept
        env.edge(CREATE, "customer-service GET /customers-api/customers/{id}");
        env.edge("order-service GET /api/orders/by-customer/{customerId}/summary",
                "customer-service GET /customers-api/customers/{id}/orders-summary");
        // ${inventory.base-url} from application-prod.yml: http://inventory-service.prod.svc.cluster.local
        assertEquals(List.of("OrderService.create", "InventoryFeignClient.getStock"),
                strings(env.edge(CREATE, "inventory-service GET /inventory/{sku}").get("via")));
    }

    @Test
    void deepChainThroughHandlerInterfaceServicesAndStaticLibraryUtils() {
        JsonNode e = env.edge(CHECKOUT, "customer-service GET /customers-api/customers/{id}");
        assertEquals(List.of("CheckoutHandlerImpl.handle", "CheckoutService.checkout", "CustomerChecks.requireActive",
                "CustomerChecks.load", "CustomerFeignClient.getCustomer"), strings(e.get("via")));
        assertEquals("lib-http-clients/src/main/java/com/acme/libs/http/CustomerChecks.java", e.get("file").asText());
        assertEquals(18, e.get("line").asInt());
        assertEquals(List.of("CheckoutHandlerImpl.handle", "CheckoutService.checkout", "FulfilmentService.payAndShip",
                        "ShippingFeignClient.createExpress"),
                strings(env.edge(CHECKOUT, "unresolved:order-service:${SHIPPING_SERVICE_URL} POST /shipments/express").get("via")));
        env.edge(CHECKOUT, "unresolved:order-service:${SHIPPING_SERVICE_URL} POST /shipments");
    }

    @Test
    void pureStaticUtilsAddNothing() {
        for (JsonNode e : env.edges) {
            String via = e.get("via").toString();
            assertFalse(via.contains("MoneyUtils") || via.contains("ShippingPayloadUtils"), e.toString());
        }
    }

    @Test
    void feignWithoutUrlOrWithUnknownPlaceholderIsAnUnresolvedNode() {
        JsonNode n = env.node("unresolved:order-service:feign:pricing-service GET /prices/{sku}");
        assertEquals("unresolved", n.get("kind").asText());
        assertEquals("feign:pricing-service", n.get("missing").asText());
        // reached through a private helper in the same class
        assertEquals(List.of("OrderService.create", "OrderService.priceOf", "PricingFeignClient.getPrice"),
                strings(env.edge(CREATE, n.get("id").asText()).get("via")));
        env.edge("notification-service POST /notifications/{userId}",
                "unresolved:notification-service:feign:user-profile-service GET /profile-api/profiles/{userId}");
        assertEquals("${SHIPPING_SERVICE_URL}",
                env.node("unresolved:order-service:${SHIPPING_SERVICE_URL} POST /shipments").get("missing").asText());
        assertTrue(env.stdout.contains("unresolved:order-service:feign:pricing-service GET /prices/{sku}"), env.stdout);
    }

    @Test
    void matchingDefaultsProfilesExternalHostsAndRecursion(@TempDir Path root) throws Exception {
        Path c = root.resolve("caller-service/src/main/java/c");
        write(c.resolve("App.java"), "package c;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(c.resolve("Api.java"), """
                package c;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Api {
                  private final Gateway gateway;
                  Api(Gateway gateway) { this.gateway = gateway; }
                  @GetMapping("/a") public void a() { gateway.go(3); }
                }
                """);
        write(c.resolve("Gateway.java"), "package c;\npublic interface Gateway { void go(int n); }\n");
        write(c.resolve("GatewayImpl.java"), """
                package c;
                @org.springframework.stereotype.Component
                public class GatewayImpl implements Gateway {
                  private final TargetClient target;
                  private final ExtClient ext;
                  GatewayImpl(TargetClient target, ExtClient ext) { this.target = target; this.ext = ext; }
                  public void go(int n) {
                    if (n > 0) { go(n - 1); }
                    Helpers.call(target, "x");
                    target.any();
                    target.missing();
                    ext.x();
                  }
                }
                """);
        write(c.resolve("Helpers.java"), """
                package c;
                public final class Helpers {
                  static Object call(TargetClient t, String id) { return t.get(id); }
                }
                """);
        write(c.resolve("TargetClient.java"), """
                package c;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.*;
                @FeignClient(name = "t", url = "${target.url:http://localhost:9001}", path = "/base")
                public interface TargetClient {
                  @GetMapping("/items/{id}") Object get(@PathVariable("id") String id);
                  @PostMapping("/any/thing") void any();
                  @GetMapping("/missing") void missing();
                }
                """);
        write(c.resolve("ExtClient.java"), """
                package c;
                @org.springframework.cloud.openfeign.FeignClient(name = "ext", url = "https://api.example.com/v1")
                public interface ExtClient { @org.springframework.web.bind.annotation.GetMapping("/x") void x(); }
                """);
        write(root.resolve("caller-service/src/main/resources/application-dev.yml"), "target.url: http://other-service:8080\n");
        Path t = root.resolve("target-service/src/main/java/t");
        write(t.resolve("App.java"), "package t;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(t.resolve("Items.java"), """
                package t;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/base")
                public class Items {
                  @GetMapping("/items/{itemId}") public Object get() { return null; }
                  @GetMapping("/items/special") public Object special() { return null; }
                  @RequestMapping("/any/thing") public void any() { }
                }
                """);
        write(root.resolve("target-service/src/main/resources/application.yml"), "server:\n  port: 9001\n");
        Path o = root.resolve("other-service/src/main/java/o");
        write(o.resolve("App.java"), "package o;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(o.resolve("Items.java"), """
                package o;
                @org.springframework.web.bind.annotation.RestController
                public class Items { @org.springframework.web.bind.annotation.GetMapping("/base/items/{id}") public Object get() { return null; } }
                """);

        Graph g = map(root, "prod", root.resolve("out-prod"));
        String a = "caller-service GET /a";
        // ${target.url:default} -> localhost:9001 -> target-service; template vars match literals both ways
        assertEquals(Set.of("target-service GET /base/items/{itemId}", "target-service GET /base/items/special",
                "target-service ANY /base/any/thing", "unresolved:target-service GET /base/missing",
                "external:https://api.example.com GET /v1/x"), g.targetsOf(a, ""));
        JsonNode get = g.edge(a, "target-service GET /base/items/{itemId}");
        assertEquals(List.of("GatewayImpl.go", "Helpers.call", "TargetClient.get"), strings(get.get("via")));
        assertEquals("caller-service/src/main/java/c/Helpers.java", get.get("file").asText());
        assertEquals(3, get.get("line").asInt());
        assertEquals("external", g.edge(a, "external:https://api.example.com GET /v1/x").get("kind").asText());
        assertEquals("external", g.node("external:https://api.example.com GET /v1/x").get("kind").asText());
        JsonNode missing = g.node("unresolved:target-service GET /base/missing");
        assertEquals("target-service", missing.get("service").asText());
        assertEquals(1, g.edges.stream().filter(e -> e.get("to").asText().equals("target-service ANY /base/any/thing")).count(),
                "recursion is walked once");

        // the profile file overrides the default: http://other-service:8080 -> other-service by host name
        Graph dev = map(root, "dev", root.resolve("out-dev"));
        assertEquals(Set.of("other-service GET /base/items/{id}"), dev.targetsOf(a, "other-service "));
    }

    @Test
    void mapHtmlHasServiceAndEndpointDownstreamViews() throws Exception {
        String html = Files.readString(out.resolve("map.html"));
        assertTrue(html.contains("L1 services") && html.contains("L2 endpoints"), "level toggle");
        assertTrue(html.contains("\"via\""), "edges embedded");
    }

    // --- helpers ---

    record Graph(List<JsonNode> edges, Map<String, JsonNode> nodes, String stdout) {

        JsonNode edge(String from, String to) {
            return edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals(to))
                    .findFirst().orElseThrow(() -> new AssertionError("no edge " + from + " -> " + to + " in " + targetsOf(from, "")));
        }

        Set<String> targetsOf(String from, String prefix) {
            Set<String> out = new TreeSet<>();
            edges.stream().filter(e -> e.get("from").asText().equals(from)).map(e -> e.get("to").asText())
                    .filter(t -> t.startsWith(prefix)).forEach(out::add);
            return out;
        }

        JsonNode node(String id) {
            JsonNode n = nodes.get(id);
            if (n == null) {
                throw new AssertionError("missing node " + id + " in " + new TreeSet<>(nodes.keySet()));
            }
            return n;
        }
    }

    private static Graph map(Path root, String profile, Path outDir) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"map", "--root", root.toString(), "--profile", profile, "--out", outDir.toString()},
                new PrintStream(o, true), new PrintStream(e, true));
        assertEquals(0, code, e.toString());
        JsonNode g = JSON.readTree(outDir.resolve("graph.json").toFile());
        List<JsonNode> edges = new ArrayList<>();
        g.get("edges").forEach(edges::add);
        Map<String, JsonNode> nodes = new HashMap<>();
        g.get("nodes").forEach(n -> nodes.put(n.get("id").asText(), n));
        for (JsonNode edge : edges) {
            assertTrue(nodes.containsKey(edge.get("to").asText()), "edge target is a node: " + edge);
        }
        return new Graph(edges, nodes, o.toString());
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
