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
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: runs `map` against test-environment/ and asserts only on graph.json and map.html. */
class MapCommandTest {

    static final Path TEST_ENV = Path.of("..", "test-environment");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path out;
    static JsonNode graph;
    static Map<String, JsonNode> nodes = new HashMap<>();
    static String stdout;

    @BeforeAll
    static void runMap() throws Exception {
        Run r = run("map", "--root", TEST_ENV.toString(), "--profile", "prod", "--out", out.toString());
        assertEquals(0, r.code, r.err);
        stdout = r.out;
        graph = JSON.readTree(out.resolve("graph.json").toFile());
        graph.get("nodes").forEach(n -> nodes.put(n.get("id").asText(), n));
    }

    @Test
    void everyJavaRepoIsAServiceOrLibraryWithAlphabeticalColorIndex() {
        List<String> names = new ArrayList<>();
        graph.get("services").forEach(s -> names.add(s.get("name").asText()));
        assertEquals(new ArrayList<>(new TreeSet<>(names)), names, "sorted");
        assertEquals(23, names.size(), names.toString());
        for (int i = 0; i < names.size(); i++) {
            JsonNode s = graph.get("services").get(i);
            assertEquals(i, s.get("colorIndex").asInt());
            assertEquals(names.get(i).startsWith("lib-") ? "library" : "service", s.get("kind").asText(), names.get(i));
        }
        assertTrue(names.stream().noneMatch(n -> n.endsWith("-terraform") || n.endsWith("-py")), names.toString());
    }

    @Test
    void endpointNodeCarriesAllFields() {
        JsonNode n = node("order-service GET /api/orders/{id}");
        assertEquals("endpoint", n.get("kind").asText());
        assertEquals("order-service", n.get("service").asText());
        assertEquals("GET", n.get("method").asText());
        assertEquals("/api/orders/{id}", n.get("path").asText());
        assertEquals("OrderController.get", n.get("handler").asText());
        assertEquals("order-service/src/main/java/com/acme/order/web/OrderController.java", n.get("file").asText());
        assertEquals(29, n.get("line").asInt());
        assertEquals("annotation", n.get("source").asText());
    }

    @Test
    void classPrefixAndMultiplePathsPerMethod() {
        assertPresent("order-service GET /api/orders", "order-service GET /api/orders/search",
                "order-service POST /api/orders", "order-service DELETE /api/orders/{id}",
                "catalog-service POST /catalog/products/batch", "catalog-service POST /catalog/products/bulk");
    }

    @Test
    void arrayClassPrefixConstantsAndRegexVariables() {
        for (String prefix : List.of("/inventory", "/v2/inventory")) {
            assertPresent("inventory-service GET " + prefix + "/{sku}",
                    "inventory-service PUT " + prefix + "/{sku}/reserve",
                    "inventory-service DELETE " + prefix + "/{sku}/reservation",
                    "inventory-service PATCH " + prefix + "/{sku}/quantity",
                    "inventory-service GET " + prefix + "/warehouse/{code}/items");
        }
        assertEquals(10, count("inventory-service"));
    }

    @Test
    void requestMappingWithMethodArrayAndSingleMethod() {
        assertPresent("customer-service GET /customers-api/customers/{id}/preferences",
                "customer-service PATCH /customers-api/customers/{id}/preferences",
                "tax-service POST /tax-api/tax/admin/reload");
    }

    @Test
    void plainControllerWithCrossClassConstants() {
        assertPresent("tax-service GET /tax-api/tax/admin/status");
        assertEquals("TaxAdminController.status", node("tax-service GET /tax-api/tax/admin/status").get("handler").asText());
        assertTrue(nodes.values().stream().noneMatch(n -> n.get("handler").asText().equals("TaxAdminController.version")));
    }

    @Test
    void prefixesWithoutLeadingSlash() {
        assertPresent("payment-service POST /payments/charge", "payment-service POST /payments/{id}/refund",
                "pricing-service GET /prices/{sku}", "pricing-service POST /prices/quote");
    }

    @Test
    void controllerImplementingHttpExchangeInterface() {
        assertPresent("shipping-service POST /shipments", "shipping-service POST /shipments/express",
                "shipping-service GET /shipments/{id}", "shipping-service DELETE /shipments/{id}");
        JsonNode n = node("shipping-service GET /shipments/{id}");
        assertEquals("ShipmentController.get", n.get("handler").asText());
        assertTrue(n.get("file").asText().endsWith("ShipmentController.java"));
        assertEquals(4, count("shipping-service"));
    }

    @Test
    void contextPathAndBasePathFromYmlAndProperties() {
        assertPresent("order-service POST /api/orders/{id}/checkout",          // yml, nested keys
                "customer-service GET /customers-api/customers/{id}",          // .properties
                "tax-service GET /tax-api/tax/rates",                          // yml
                "user-profile-service GET /profile-api/profiles/{userId}");    // spring.webflux.base-path
        assertPresent("warehouse-service GET /warehouse/stock/hot");          // no context path
    }

    @Test
    void unparseableFileDoesNotStopTheRun() {
        assertPresent("report-service GET /reports/{type}", "report-service POST /reports/export");
        assertTrue(stdout.contains("UnparseableLegacyExporter.java"), stdout);
    }

    @Test
    void onlyAnnotatedEndpointsAndNoDuplicates() {
        Set<String> ids = new TreeSet<>();
        graph.get("nodes").forEach(n -> assertTrue(ids.add(n.get("id").asText()), "duplicate " + n.get("id")));
        nodes.values().forEach(n -> assertEquals(n.get("service").asText() + " " + n.get("method").asText()
                + " " + n.get("path").asText(), n.get("id").asText()));
        assertTrue(graph.get("edges").isArray());
        assertTrue(graph.get("flows").isObject());
    }

    @Test
    void mapHtmlIsSelfContained() throws Exception {
        String html = Files.readString(out.resolve("map.html"));
        assertFalse(html.matches("(?s).*<script[^>]*\\ssrc=.*"), "no external scripts");
        assertFalse(html.matches("(?s).*<link[^>]*\\shref=.*"), "no external stylesheets");
        assertTrue(html.contains("cytoscape"));
        assertTrue(html.contains("cytoscapeDagre"));
        assertTrue(html.contains("\"order-service GET /api/orders/{id}\""), "graph embedded");
        for (String color : List.of("#2e9e4f", "#f08c00", "#8e44ad", "#e0b000", "#d63031")) {
            assertTrue(html.contains(color), color);
        }
        assertTrue(html.contains("(i * 137.5) % 360"), "pastel service hue");
        assertFalse(html.contains("/*@"), "all placeholders filled");
    }

    @Test
    void profileSelectsContextPath(@TempDir Path root) throws Exception {
        Path svc = root.resolve("svc");
        writeApp(svc, "Api", "@org.springframework.web.bind.annotation.GetMapping(\"/ping\")");
        write(svc.resolve("src/main/resources/application.yml"), """
                server.servlet.context-path: /base
                ---
                spring.config.activate.on-profile: prod
                server.servlet.context-path: /doc
                """);
        assertEquals(Set.of("svc GET /base/ping"), ids(root, "dev"));
        assertEquals(Set.of("svc GET /doc/ping"), ids(root, "prod"));
        write(svc.resolve("src/main/resources/application-prod.properties"), "server.servlet.context-path=/file\n");
        assertEquals(Set.of("svc GET /file/ping"), ids(root, "prod"));
        write(svc.resolve("src/main/resources/application-prod.yml"), "spring:\n  webflux:\n    base-path: /flux\n");
        assertEquals(Set.of("svc GET /file/ping"), ids(root, "prod"), "servlet context path wins over base path");
    }

    @Test
    void nonJavaFoldersSkippedAndLibraryWithoutBootApp(@TempDir Path root) throws Exception {
        write(root.resolve("infra/main.tf"), "resource \"x\" \"y\" {}\n");
        write(root.resolve("infra/application.yml"), "server.servlet.context-path: /nope\n");
        write(root.resolve("lib/src/main/java/Util.java"), "public class Util {}\n");
        write(root.resolve("lib/src/main/java/Broken.java"), "public class Broken {\n");
        Path o = root.resolve("out");
        assertEquals(0, run("map", "--root", root.toString(), "--profile", "prod", "--out", o.toString()).code);
        JsonNode g = JSON.readTree(o.resolve("graph.json").toFile());
        assertEquals(1, g.get("services").size());
        assertEquals("lib", g.get("services").get(0).get("name").asText());
        assertEquals("library", g.get("services").get(0).get("kind").asText());
    }

    @Test
    void usageAndDiscoverStub() {
        assertEquals(2, run().code);
        assertEquals(2, run("map", "--root", TEST_ENV.toString(), "--bogus", "x").code);
        assertEquals(0, run("discover", "--root", TEST_ENV.toString(), "--profile", "prod").code);
    }

    // --- helpers ---

    private static void assertPresent(String... ids) {
        for (String id : ids) {
            assertNotNull(nodes.get(id), "missing " + id + " in " + new TreeSet<>(nodes.keySet()));
        }
    }

    private static JsonNode node(String id) {
        assertPresent(id);
        return nodes.get(id);
    }

    private static long count(String service) {
        return nodes.values().stream().filter(n -> n.get("service").asText().equals(service)).count();
    }

    private static Set<String> ids(Path root, String profile) throws Exception {
        Path o = Files.createTempDirectory(root.getParent(), "out");
        Run r = run("map", "--root", root.toString(), "--profile", profile, "--out", o.toString());
        assertEquals(0, r.code, r.err);
        Set<String> ids = new TreeSet<>();
        JSON.readTree(o.resolve("graph.json").toFile()).get("nodes").forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    private static void writeApp(Path repo, String controller, String mapping) throws Exception {
        Path pkg = repo.resolve("src/main/java/demo");
        write(pkg.resolve("App.java"), "package demo;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(pkg.resolve(controller + ".java"), "package demo;\n@org.springframework.web.bind.annotation.RestController\n"
                + "public class " + controller + " {\n  " + mapping + "\n  public String ping() { return \"\"; }\n}\n");
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(o, true), new PrintStream(e, true));
        return new Run(code, o.toString(), e.toString());
    }
}
