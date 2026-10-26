package com.endpointmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Black-box: which service an OpenAPI file documents is decided by its operations (PR #27 review). Workspace:
 * order-service (context path /api, port 8081: GET /orders, GET /orders/{id}, POST /orders) and inventory-service
 * (GET /stock/{sku}, POST /stock/reserve); each test adds OpenAPI files.
 */
class OpenApiAttributionTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String ORDER_OPS = """
            "/orders": {"get": {}, "post": {}}, "/orders/{orderId}": {"get": {}},
            "/orders/{id}/invoice": {"get": {"operationId": "invoice", "summary": "PDF invoice"}}""";

    @TempDir
    Path root;

    record Out(String report, Map<String, JsonNode> nodes, List<String> edges) {
        String section(String title) {
            String s = report.substring(report.indexOf("## " + title + "\n"));
            return s.substring(0, s.indexOf("\n## ", 3));
        }

        List<String> ids(String prefix) {
            return nodes.keySet().stream().filter(id -> id.startsWith(prefix)).toList();
        }
    }

    void workspace() throws Exception {
        Path o = root.resolve("order-service");
        write(o.resolve("src/main/resources/application.yml"), "server:\n  port: 8081\n  servlet:\n    context-path: /api\n");
        write(o.resolve("src/main/java/o/App.java"), "package o;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(o.resolve("src/main/java/o/Orders.java"), """
                package o;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/orders")
                public class Orders {
                  @GetMapping public void list() { }
                  @GetMapping("/{id}") public void get() { }
                  @PostMapping public void create() { }
                }
                """);
        Path i = root.resolve("inventory-service");
        write(i.resolve("src/main/java/i/App.java"), "package i;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(i.resolve("src/main/java/i/Stock.java"), """
                package i;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/stock")
                public class Stock {
                  @GetMapping("/{sku}") public void get() { }
                  @PostMapping("/reserve") public void reserve() { }
                }
                """);
    }

    /** The order-service's own spec with these servers documents order-service: only the invoice is extra. */
    void assertOrderSpecUsed(String servers) throws Exception {
        Path f = root.resolve("order-service/openapi.json");
        write(f, "{\"openapi\": \"3.0.1\"" + servers + ", \"paths\": {" + ORDER_OPS + "}}");
        Out out = run();
        assertEquals(List.of("order-service GET /api/orders/{id}/invoice"), openapiOnly(out), servers + "\n" + out.report);
        assertTrue(out.section("OpenAPI vs code mismatches: only in code").contains("None."), out.report);
        assertTrue(out.section("OpenAPI files used").contains("- `order-service/openapi.json`: documents order-service (3 of 4"
                + " operations match its code; paths prefixed with `/api`)"), out.report);
        Files.delete(f);
    }

    @Test
    void ownSpecWithPublicOrVariableServerUrlIsStillCrossChecked() throws Exception { // findings 1, 4
        workspace();
        assertOrderSpecUsed(", \"servers\": [{\"url\": \"https://api.acme.com/api\"}]");
        assertOrderSpecUsed(", \"servers\": [{\"url\": \"http://order-service.prod.acme.io/api\"}]");
        assertOrderSpecUsed(", \"servers\": [{\"url\": \"https://{env}.acme.com/api\"}]");
        assertOrderSpecUsed(", \"servers\": [{\"url\": \"{scheme}://{host}/api\", \"variables\": {\"scheme\": {\"default\": \"http\"},"
                + " \"host\": {\"default\": \"localhost\"}}}]");
        assertOrderSpecUsed(", \"servers\": [{\"url\": \"http://localhost:8081/api\"}]");
    }

    @Test
    void contextPathAddedWhenServersLeaveItOut() throws Exception { // finding 3
        workspace();
        assertOrderSpecUsed("");
        assertOrderSpecUsed(", \"servers\": [{\"url\": \"/\"}]");
        assertOrderSpecUsed(", \"servers\": [{\"url\": \"http://localhost:8081\"}]");
    }

    @Test
    void vendoredSpecsOfOtherServicesOrPartnersAddNothingToTheRepoHoldingThem() throws Exception { // finding 2
        workspace();
        String inv = "\"paths\": {\"/stock/{sku}\": {\"get\": {}}, \"/stock/reserve\": {\"post\": {}}}}";
        write(root.resolve("order-service/docs/clients/openapi-inventory.json"),
                "{\"openapi\": \"3.0.1\", \"servers\": [{\"url\": \"http://inventory-service:8080\"}], " + inv);
        write(root.resolve("order-service/src/main/resources/openapi/openapi-inventory.yaml"),
                "openapi: 3.0.1\npaths:\n  /stock/{sku}:\n    get: {}\n  /stock/reserve:\n    post: {}\n");
        write(root.resolve("order-service/docs/openapi-partner.json"),
                "{\"swagger\": \"2.0\", \"basePath\": \"/v1\", \"paths\": {\"/charges\": {\"post\": {}}}}");
        Out out = run();
        assertEquals(List.of(), openapiOnly(out), out.report);
        assertFalse(out.report.contains("order-service GET /api/orders` ("), "order-service has no spec of its own: " + out.report);
        String used = out.section("OpenAPI files used");
        assertTrue(used.contains("- `order-service/docs/clients/openapi-inventory.json`: documents inventory-service (2 of 2"), used);
        assertTrue(used.contains("- `order-service/src/main/resources/openapi/openapi-inventory.yaml`: documents inventory-service"), used);
        assertTrue(out.section("OpenAPI files skipped").contains("- `order-service/docs/openapi-partner.json`: not used: could not tell"
                + " which service it documents (none of its 1 operations match any service's code)"), out.report);
    }

    @Test
    void pathItemRefs() throws Exception { // finding 5
        workspace();
        write(root.resolve("order-service/openapi.json"), """
                {"openapi": "3.1.0", "paths": {
                  "/orders": {"get": {}, "post": {}},
                  "/orders/{id}": {"$ref": "paths/order.json"},
                  "/orders/{id}/invoice": {"$ref": "#/components/pathItems/inv"}},
                 "components": {"pathItems": {"inv": {"get": {"operationId": "invoice"}}}}}
                """);
        Out out = run();
        assertEquals(List.of("order-service GET /api/orders/{id}/invoice"), openapiOnly(out), out.report);
        assertEquals("invoice", out.nodes.get("order-service GET /api/orders/{id}/invoice").get("operationId").asText());
        assertTrue(out.section("OpenAPI files skipped").contains("- `order-service/openapi.json`: `/orders/{id}`: $ref paths/order.json"
                + " not followed"), out.report);
    }

    @Test
    void openapiDirOutsideTheRootAddsToAutoFindAndIsAttributedByContent(@TempDir Path docs) throws Exception { // findings 6, 9
        workspace();
        write(root.resolve("order-service/openapi.json"), "{\"openapi\": \"3.0.1\", \"paths\": {" + ORDER_OPS + "}}");
        write(docs.resolve("inventory/openapi.json"), "{\"openapi\": \"3.0.1\", \"paths\": {\"/stock/{sku}\": {\"get\": {}},"
                + " \"/stock/{sku}/history\": {\"get\": {}}}}");
        write(docs.resolve("inventory-service.json"), "{\"openapi\": \"3.0.1\", \"paths\": {\"/stock/reserve\": {\"post\": {}}}}");
        write(docs.resolve("notes.json"), "{\"hello\": 1}");
        Out out = run("--openapi-dir", docs.toString());
        assertEquals(List.of("inventory-service GET /stock/{sku}/history", "order-service GET /api/orders/{id}/invoice"),
                openapiOnly(out), out.report);
        assertEquals("openapi-dir:inventory/openapi.json", out.nodes.get("inventory-service GET /stock/{sku}/history").get("file").asText());
        assertTrue(out.section("OpenAPI files used").contains("- `openapi-dir:inventory-service.json`: documents inventory-service"),
                out.report);
        assertTrue(out.section("OpenAPI vs code mismatches: only in code").contains("None."), out.report);
        assertFalse(out.report.contains("notes.json"), out.report);
        assertFalse(out.report.contains(docs.toString()), "no machine-specific paths: " + out.report);
    }

    @Test
    void externalMatchPrefersLiteralSegmentsAndIgnoresDefaultPorts() throws Exception { // finding 10
        workspace();
        Path o = root.resolve("order-service/src/main/java/o");
        write(o.resolve("Pay.java"), """
                package o;
                @org.springframework.cloud.openfeign.FeignClient(name = "pay", url = "https://api.x.com:443")
                public interface Pay { @org.springframework.web.bind.annotation.GetMapping("/v1/charges/search") void search(); }
                """);
        write(o.resolve("Search.java"), """
                package o;
                @org.springframework.web.bind.annotation.RestController
                public class Search { Pay pay; @org.springframework.web.bind.annotation.GetMapping("/find") public void f() { pay.search(); } }
                """);
        write(root.resolve("order-service/docs/openapi-x.yaml"), """
                openapi: 3.0.1
                servers: [{url: "https://api.x.com"}]
                paths:
                  /v1/charges/{id}: {get: {operationId: retrieve}}
                  /v1/charges/search: {get: {operationId: search}}
                """);
        Out out = run();
        JsonNode call = out.nodes.values().stream().filter(n -> "external".equals(n.path("kind").asText())).findFirst().orElseThrow();
        assertEquals("search", call.path("operationId").asText(), call.toString());
        assertEquals("/v1/charges/search", call.path("operation").asText());
    }

    @Test
    void toolConfigsAndBuildOutputAreIgnored() throws Exception { // finding 11
        workspace();
        write(root.resolve("order-service/openapitools.json"), "{\"generator-cli\": {\"version\": \"7.0.0\"}}");
        write(root.resolve("order-service/src/main/resources/swagger-config.json"), "{\"urls\": []}");
        write(root.resolve("order-service/build/openapi.json"), "{\"openapi\": \"3.0.1\", \"paths\": {" + ORDER_OPS + "}}");
        Out out = run();
        assertTrue(out.section("OpenAPI files skipped").contains("None."), out.report);
        assertTrue(out.section("OpenAPI files used").contains("None."), out.report);
    }

    @Test
    void largePrettyPrintedSpecLoadsInLinearTime() throws Exception { // finding 7
        workspace();
        StringBuilder sb = new StringBuilder("{\n  \"openapi\": \"3.0.1\",\n  \"paths\": {\n"
                + "    \"/orders\": {\"get\": {}, \"post\": {}},\n    \"/orders/{id}\": {\"get\": {}}");
        for (int i = 0; i < 3000; i++) {
            sb.append(",\n    \"/orders/x").append(i).append("/y\": {\n      \"get\": {\n        \"summary\": \"s\"\n      }\n    }");
        }
        write(root.resolve("order-service/openapi.json"), sb.append("\n  }\n}\n").toString());
        long t = System.nanoTime();
        Out out = run();
        assertTrue(System.nanoTime() - t < 20_000_000_000L, "took " + (System.nanoTime() - t) / 1_000_000 + " ms");
        assertEquals(6 + 5 * 2999, out.nodes.get("order-service GET /api/orders/x2999/y").get("line").asInt());
    }

    private static List<String> openapiOnly(Out out) {
        return out.nodes.values().stream().filter(n -> "openapi-only".equals(n.path("source").asText()))
                .peek(n -> assertEquals("(OpenAPI only)", n.get("handler").asText())).map(n -> n.get("id").asText()).toList();
    }

    private Out run(String... extra) throws Exception {
        Path res = root.resolve("conf/resolutions.yml");
        Files.deleteIfExists(res);
        List<String> args = new ArrayList<>(List.of("--root", root.toString(), "--profile", "prod", "--resolutions", res.toString()));
        args.addAll(List.of(extra));
        main("discover", args);
        args.addAll(List.of("--out", root.resolve("conf/out").toString()));
        main("map", args);
        Map<String, JsonNode> nodes = new TreeMap<>();
        JsonNode g = JSON.readTree(root.resolve("conf/out/graph.json").toFile());
        g.get("nodes").forEach(n -> nodes.put(n.get("id").asText(), n));
        List<String> edges = new ArrayList<>();
        g.get("edges").forEach(e -> edges.add(e.get("from").asText() + " -> " + e.get("to").asText()));
        return new Out(Files.readString(root.resolve("conf/discover-report.md")), nodes, edges);
    }

    private static void main(String cmd, List<String> args) {
        List<String> all = new ArrayList<>(List.of(cmd));
        all.addAll(args);
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(0, Main.run(all.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(e, true)), e.toString());
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
