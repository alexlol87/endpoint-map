package com.endpointmap;

import static com.endpointmap.RepositoryTablesTest.list;
import static com.endpointmap.RepositoryTablesTest.run;
import static com.endpointmap.RepositoryTablesTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: graph.json {@code flows}, the code-flow tree per endpoint (issue #13). */
class FlowTreeTest {

    static final Set<String> TYPES = Set.of("seq", "call", "if", "else", "switch", "case", "loop", "try", "catch", "oneOf",
            "parallel", "fallback", "retry", "edge", "cycle", "unresolved");

    @TempDir
    static Path dir;
    static JsonNode graph;

    @BeforeAll
    static void map() throws Exception {
        String res = dir.resolve("resolutions.yml").toString();
        run("discover", RepositoryTablesTest.ENV, "--resolutions", res);
        run("map", RepositoryTablesTest.ENV, "--resolutions", res, "--out", dir.resolve("out").toString());
        graph = new ObjectMapper().readTree(dir.resolve("out/graph.json").toFile());
    }

    /** Every root-to-leaf path of {@code endpoint}'s flow, as "type label > ... > edge to" (cycle leaves as their label). */
    static List<String> paths(JsonNode graph, String endpoint) {
        List<String> out = new ArrayList<>();
        JsonNode root = graph.get("flows").get(endpoint);
        assertEquals("seq", root.get("type").asText());
        root.get("children").forEach(c -> walk(graph, c, "", out));
        return out;
    }

    private static void walk(JsonNode graph, JsonNode n, String prefix, List<String> out) {
        String type = n.get("type").asText();
        assertTrue(TYPES.contains(type), type);
        if (n.has("edge")) {
            out.add(prefix + type + " " + graph.get("edges").get(n.get("edge").asInt()).get("to").asText());
            return;
        }
        String here = prefix + type + (n.has("label") ? " " + n.get("label").asText() : "");
        if (!n.has("children")) {
            out.add(here);
            return;
        }
        n.get("children").forEach(c -> walk(graph, c, here + " > ", out));
    }

    /** Contract: every edge is a leaf at least once, maybe under several paths, never twice under one parent. */
    @Test
    void everyEdgeIsALeafOfItsEndpointsFlowAndNoLeafRepeatsUnderOneParent() {
        List<JsonNode> edges = list(graph.get("edges"));
        Set<Integer> seen = new TreeSet<>();
        graph.get("flows").fields().forEachRemaining(f -> collect(f.getValue(), f.getKey(), edges, seen));
        assertEquals(edges.size(), seen.size()); // the leaf set equals the edge set (L1/L2)
    }

    private static void collect(JsonNode n, String endpoint, List<JsonNode> edges, Set<Integer> seen) {
        Set<String> kids = new java.util.HashSet<>();
        n.path("children").forEach(c -> assertTrue(kids.add(c.toString()), "repeated under one parent in " + endpoint + ": " + c));
        if (n.has("edge")) {
            int i = n.get("edge").asInt();
            assertEquals(endpoint, edges.get(i).get("from").asText());
            assertEquals(edges.get(i).get("to").asText().startsWith("unresolved:") ? "unresolved" : "edge", n.get("type").asText());
            seen.add(i);
        }
        n.path("children").forEach(c -> collect(c, endpoint, edges, seen));
    }

    @Test
    void deepChainThroughHandlerServicesAndUtilsWithTernary() {
        List<String> p = paths(graph, "order-service POST /api/orders/{id}/checkout");
        // controller -> handler impl -> service -> pricing -> tax (ternary else) -> utils; MoneyUtils leads to no edge
        assertTrue(p.contains("call CheckoutHandlerImpl.handle > call CheckoutService.checkout > call OrderPricingService.finalTotal"
                + " > call OrderTaxService.rateFor > else \"XX\".equals(region) > call TaxRestUtils.fetchRate > edge tax-service GET /tax-api/tax/rates/{region}"), p.toString());
        assertTrue(p.contains("call CheckoutHandlerImpl.handle > call CheckoutService.checkout > call FulfilmentService.payAndShip"
                + " > if express > unresolved unresolved:order-service:${SHIPPING_SERVICE_URL} POST /shipments/express"), p.toString());
        assertTrue(p.contains("call CheckoutHandlerImpl.handle > call CheckoutService.checkout > call FulfilmentService.payAndShip"
                + " > else express > unresolved unresolved:order-service:${SHIPPING_SERVICE_URL} POST /shipments"), p.toString());
        assertFalse(p.toString().contains("MoneyUtils"), p.toString());
    }

    @Test
    void ifElseForeachTryCatchFinallyAndStreamForEach() {
        List<String> p = paths(graph, "order-service POST /api/orders");
        assertTrue(p.contains("call OrderService.create > loop for (String sku : request.skus())"
                + " > if ((Number) stock.get(\"available\")).intValue() > 0 > edge inventory-service PUT /inventory/{sku}/reserve"), p.toString());
        assertTrue(p.contains("call OrderService.create > loop for (String sku : request.skus())"
                + " > else ((Number) stock.get(\"available\")).intValue() > 0 > edge inventory-service PATCH /inventory/{sku}/quantity"), p.toString());
        assertTrue(p.contains("call OrderService.create > try > call PaymentClient.charge"
                + " > unresolved unresolved:order-service:${PAYMENT_SERVICE_URL} POST /payments/charge"), p.toString());
        assertTrue(p.contains("call OrderService.create > catch FeignException > loop request.skus().forEach"
                + " > edge inventory-service DELETE /inventory/{sku}/reservation"), p.toString());
        assertTrue(p.contains("call OrderService.create > try finally > edge db:order-service:orders"), p.toString());
    }

    @Test
    void switchStatementAndExpressionCasesWhileAndDoLoops() {
        assertTrue(paths(graph, "order-service POST /api/orders/{id}/cancel").contains("call OrderService.cancel > switch order.getStatus()"
                + " > case \"PAID\" > call PaymentClient.refund > unresolved unresolved:order-service:${PAYMENT_SERVICE_URL} POST /payments/{*}/refund"));
        assertTrue(paths(graph, "pricing-service POST /prices/quote").contains("call PriceService.quote > switch String.valueOf(coupon)"
                + " > case \"VIP\" > call PriceService.taxRate > edge tax-service GET /tax-api/tax/rates/{region}"));
        assertTrue(paths(graph, "inventory-service PUT /inventory/{sku}/reserve").contains("call StockService.reserve"
                + " > loop while (updated == 0 && repository.existsBySku(sku)) > edge db:inventory-service:stock_item"));
        assertTrue(paths(graph, "report-service GET /reports/{type}").contains("call ReportService.build"
                + " > loop do ... while (!chunk.isEmpty()) > edge db:report-service:report_rows"));
    }

    @Test
    void aCallReachedFromBothBranchesIsUnderBoth() {
        List<String> p = paths(graph, "notification-service POST /notifications/{userId}");
        String cond = "Boolean.TRUE.equals(profile.get(\"smsOptIn\"))";
        assertTrue(p.contains("if " + cond + " > call SmsNotifier.notify > edge notification-service POST /notifications/sms"), p.toString());
        assertTrue(p.contains("else " + cond + " > call PushNotifier.notify > edge external:https://fcm.googleapis.com POST /v1/projects/acme/messages:send"), p.toString());
        // one edge, two places: the stream loop and the direct call in ReportService.build
        List<String> r = paths(graph, "report-service GET /reports/{type}");
        assertTrue(r.contains("call ReportService.build > loop rows.stream().filter(r -> r.get(\"orderId\") != null).forEach"
                + " > call InternalRestTemplateClient.fetch > edge order-service GET /api/orders/{id}"), r.toString());
        assertTrue(r.contains("call ReportService.build > call InternalRestTemplateClient.fetch > edge order-service GET /api/orders/{id}"), r.toString());
    }

    @Test
    void elseOfAPrunedIfStillCarriesTheCondition() {
        assertTrue(paths(graph, "pricing-service GET /prices/{sku}").contains("call PriceService.price > else \"EUR\".equals(currency)"
                + " > call PriceService.convert > edge external:https://api.exchangerate.host GET /convert"));
    }

    @Test
    void crossServiceCyclesEndInACycleNode() {
        // order -> customer -> order
        assertTrue(paths(graph, "order-service GET /api/orders/by-customer/{customerId}/summary").contains(
                "call OrderService.summaryForCustomer > cycle ↻ back to order-service GET /api/orders/by-customer/{customerId}/summary"));
        assertTrue(paths(graph, "notification-service POST /notifications/{userId}").contains("if Boolean.TRUE.equals(profile.get(\"smsOptIn\"))"
                + " > call SmsNotifier.notify > cycle ↻ back to notification-service POST /notifications/{userId}"));
    }

    @Test
    void classicForStreamMapRecursionAndSilentUtils(@TempDir Path root) throws Exception {
        Path c = root.resolve("flow-service/src/main/java/f");
        write(c.resolve("App.java"), "package f;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(c.resolve("Api.java"), """
                package f;
                import java.util.List;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Api {
                  private final Svc svc;
                  Api(Svc svc) { this.svc = svc; }
                  @GetMapping("/f") public Object f(List<String> ids) { return svc.run(ids, 3); }
                }
                """);
        write(c.resolve("Svc.java"), """
                package f;
                import java.util.List;
                @org.springframework.stereotype.Service
                public class Svc {
                  private final Ext ext;
                  Svc(Ext ext) { this.ext = ext; }
                  public Object run(List<String> ids, int n) {
                    Util.pure(n);
                    for (int i = 0; i < n; i++) { ext.a(); }
                    if (n > 0) { run(ids, n - 1); }
                    ids.parallelStream().map(id -> ext.b()).toList();
                    reactor.core.publisher.Flux.fromStream(ids.stream()).map(id -> ext.a());
                    return ids.stream().map(id -> ext.b()).toList();
                  }
                }
                """);
        write(c.resolve("Util.java"), "package f;\npublic final class Util { static int pure(int n) { return n * 2; } }\n");
        write(c.resolve("Ext.java"), """
                package f;
                import org.springframework.web.bind.annotation.GetMapping;
                @org.springframework.cloud.openfeign.FeignClient(name = "ext", url = "https://api.example.com")
                public interface Ext { @GetMapping("/a") void a(); @GetMapping("/b") Object b(); }
                """);
        Path out = root.resolve("out");
        String res = out.resolve("resolutions.yml").toString();
        run("discover", root, "--resolutions", res);
        run("map", root, "--resolutions", res, "--out", out.toString());
        JsonNode g = new ObjectMapper().readTree(out.resolve("graph.json").toFile());
        // Flux.fromStream(..).map is no Java stream loop
        assertEquals(List.of("call Svc.run > loop for (int i = 0; i < n; i++) > edge external:https://api.example.com GET /a",
                        "call Svc.run > loop ids.parallelStream().map > edge external:https://api.example.com GET /b",
                        "call Svc.run > edge external:https://api.example.com GET /a",
                        "call Svc.run > loop ids.stream().map > edge external:https://api.example.com GET /b"),
                paths(g, "flow-service GET /f"));
    }
}
