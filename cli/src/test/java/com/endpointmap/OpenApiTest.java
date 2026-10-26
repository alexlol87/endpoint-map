package com.endpointmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** Black-box: OpenAPI cross-check and external API matching (issue #9), one test per row of the README table. */
class OpenApiTest {

    static final Path TEST_ENV = Path.of("..", "test-environment");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path dir;
    static Map<String, JsonNode> nodes = new HashMap<>();
    static String report;

    @BeforeAll
    static void discoverAndMap() throws Exception {
        Path res = dir.resolve("resolutions.yml");
        // Stripe's host is an env var: map it to the external host so its calls meet openapi-stripe.json
        Files.writeString(res, "payment-service:\n  properties:\n    \"${PAYMENT_HOST}\": \"external:https://api.stripe.com\"\n");
        run("discover", "--root", TEST_ENV.toString(), "--profile", "prod", "--resolutions", res.toString());
        report = Files.readString(dir.resolve("discover-report.md"));
        run("map", "--root", TEST_ENV.toString(), "--profile", "prod", "--resolutions", res.toString(),
                "--out", dir.resolve("out").toString());
        JSON.readTree(dir.resolve("out/graph.json").toFile()).get("nodes").forEach(n -> nodes.put(n.get("id").asText(), n));
    }

    @Test
    void orderServiceOpenApi3WithServersPrefix() {
        openapiOnly("order-service GET /api/orders/{id}/invoice", "order-service/openapi.json", 20, "(OpenAPI only)");
        assertEquals("invoice", nodes.get("order-service GET /api/orders/{id}/invoice").get("operationId").asText());
        codeOnly("order-service POST /api/orders/{id}/cancel", "order-service/openapi.json");
        assertEquals("annotation", nodes.get("order-service POST /api/orders/{id}/cancel").get("source").asText());
        assertEquals("annotation", nodes.get("order-service GET /api/orders/{id}").get("source").asText(), "documented and in code");
    }

    @Test
    void pricingServiceApiDocs() {
        openapiOnly("pricing-service POST /prices/bulk", "pricing-service/api-docs.json", 6, "(OpenAPI only)");
        codeOnly("pricing-service POST /prices/quote", "pricing-service/api-docs.json");
    }

    @Test
    void reportServiceSwagger2() {
        openapiOnly("report-service GET /reports/{type}/pdf", "report-service/swagger.json", 7, "(OpenAPI only)");
        codeOnly("report-service POST /reports/export", "report-service/swagger.json");
    }

    @Test
    void catalogService() {
        openapiOnly("catalog-service PUT /catalog/products/{sku}", "catalog-service/openapi.json", 6, "(OpenAPI only)");
        codeOnly("catalog-service POST /catalog/products/bulk", "catalog-service/openapi.json");
        assertEquals("annotation", nodes.get("catalog-service POST /catalog/products/batch").get("source").asText());
    }

    @Test
    void searchServiceFacetsBuiltInALoopComeFromOpenApi() {
        for (String f : List.of("brand", "color", "size")) {
            openapiOnly("search-service GET /search/facets/" + f, "search-service/openapi-search.json", -1, "(OpenAPI only)");
        }
        assertEquals("router", nodes.get("search-service GET /search/products/{sku}").get("source").asText());
    }

    @Test
    void auditServiceMatchesExactly() {
        assertFalse(section("OpenAPI vs code mismatches: only in OpenAPI").contains("audit-service"), report);
        assertFalse(section("OpenAPI vs code mismatches: only in code").contains("audit-service"), report);
        assertEquals("annotation", nodes.get("audit-service POST /audit/events").get("source").asText());
        assertEquals("annotation", nodes.get("audit-service GET /audit/events/{id}").get("source").asText());
    }

    @Test
    void stripeExternalOpenApi() {
        JsonNode charge = nodes.get("external:https://api.stripe.com POST /v1/charges");
        assertEquals("/v1/charges", charge.get("operation").asText(), charge.toString());
        assertEquals("payment-service/docs/external/openapi-stripe.json", charge.get("openapi").asText());
        assertEquals("/v1/refunds", nodes.get("external:https://api.stripe.com POST /v1/refunds").get("operation").asText());
        JsonNode retrieve = nodes.get("external:https://api.stripe.com GET /v1/charges/{id}");
        assertNull(retrieve.get("operation"), retrieve.toString());
        assertTrue(section("External calls not in their OpenAPI").contains("- `external:https://api.stripe.com GET /v1/charges/{id}`:"
                + " no operation of `payment-service/docs/external/openapi-stripe.json` matches GET /v1/charges/{id}"), report);
        assertFalse(nodes.keySet().stream().anyMatch(id -> id.startsWith("payment-service") && id.contains("/v1/")),
                "an external OpenAPI adds no endpoints to the service holding it");
    }

    @Test
    void siemVendorExternalOpenApiNextToTheServicesOwn() {
        JsonNode get = nodes.get("external:https://api.siem-vendor.example.com GET /v2/events/{id}");
        assertEquals("/v2/events/{eventId}", get.get("operation").asText(), "any var name matches; the template is OpenAPI's");
        assertEquals("audit-service/openapi/openapi-siem-vendor.json", get.get("openapi").asText());
        assertEquals("/v2/events", nodes.get("external:https://api.siem-vendor.example.com POST /v2/events").get("operation").asText());
        assertFalse(section("External calls not in their OpenAPI").contains("siem-vendor"), report);
        assertFalse(nodes.keySet().stream().anyMatch(id -> id.startsWith("audit-service") && id.contains("/v2/")), nodes.keySet().toString());
    }

    @Test
    void nonJavaRepoIsSkipped() {
        assertFalse(nodes.keySet().stream().anyMatch(id -> id.contains("/scores/")), nodes.keySet().toString());
        assertFalse(report.contains("ml-scoring-py"), report);
    }

    @Test
    void openapiDirSwagger2HostYamlExternalAndBrokenFiles(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(a.resolve("Items.java"), "package a;\n@org.springframework.cloud.openfeign.FeignClient(name = \"items\", url = \"http://svc-b\")\n"
                + "public interface Items { @org.springframework.web.bind.annotation.GetMapping(\"/v2/items/{id}\") void get(); }\n");
        write(a.resolve("Vendor.java"), "package a;\n@org.springframework.cloud.openfeign.FeignClient(name = \"v\", url = \"https://api.vendor.com/base\")\n"
                + "public interface Vendor { @org.springframework.web.bind.annotation.GetMapping(\"/things/{id}\") void get(); }\n");
        write(a.resolve("Api.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  Items items; Vendor vendor;
                  @org.springframework.web.bind.annotation.GetMapping("/x")
                  public void x() { items.get(); vendor.get(); }
                }
                """);
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(b.resolve("Api.java"), """
                package b;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Api {
                  @GetMapping("/v2/other") public void other() { }
                  @GetMapping("/v2/extra") public void extra() { }
                }
                """);
        // auto-found too, but none of its operations is in code: not used
        write(root.resolve("svc-b/openapi.json"), "{\"openapi\":\"3.0.0\",\"paths\":{\"/ignored\":{\"get\":{}}}}");
        Path docs = root.resolve("docs");
        write(docs.resolve("b/swagger.json"), """
                {"swagger": "2.0", "host": "svc-b", "basePath": "/v2",
                 "paths": {"/items/{itemId}": {"get": {}}, "/other": {"get": {}}}}
                """);
        write(docs.resolve("openapi-vendor.yaml"), """
                openapi: 3.0.0
                servers:
                  - url: https://api.vendor.com/base
                paths:
                  /things/{thingId}:
                    get:
                      operationId: getThing
                      summary: Fetch a thing
                """);
        write(docs.resolve("openapi-broken.json"), "{");
        write(docs.resolve("swagger-notes.json"), "{\"hello\": 1}");
        Path res = root.resolve("conf/resolutions.yml");
        run("discover", "--root", root.toString(), "--profile", "prod", "--resolutions", res.toString(), "--openapi-dir", docs.toString());
        String md = Files.readString(root.resolve("conf/discover-report.md"));
        run("map", "--root", root.toString(), "--profile", "prod", "--resolutions", res.toString(), "--openapi-dir", docs.toString(),
                "--out", root.resolve("out").toString());
        JsonNode g = JSON.readTree(root.resolve("out/graph.json").toFile());
        Map<String, JsonNode> n = new HashMap<>();
        g.get("nodes").forEach(x -> n.put(x.get("id").asText(), x));
        List<String> edges = new ArrayList<>();
        g.get("edges").forEach(e -> edges.add(e.get("from").asText() + " -> " + e.get("to").asText()));

        assertEquals("openapi-only", n.get("svc-b GET /v2/items/{itemId}").get("source").asText(), n.keySet().toString());
        assertTrue(edges.contains("svc-a GET /x -> svc-b GET /v2/items/{itemId}"), "a call reaches an openapi-only endpoint: " + edges);
        assertNull(n.get("svc-b GET /ignored"), "a file matching no service's code adds nothing");
        assertTrue(md.contains("`svc-b GET /v2/extra` (Api.extra, svc-b/src/main/java/b/Api.java:6) is not in `"), md);
        assertFalse(md.contains("`svc-b GET /v2/other`"), md);
        JsonNode thing = n.get("external:https://api.vendor.com GET /base/things/{id}");
        assertEquals("/base/things/{thingId}", thing.get("operation").asText(), thing.toString());
        assertEquals("getThing", thing.get("operationId").asText());
        assertEquals("Fetch a thing", thing.get("summary").asText());
        String skipped = md.substring(md.indexOf("## OpenAPI files skipped"));
        assertTrue(skipped.contains("openapi-broken.json`: could not be read, skipped"), skipped);
        assertTrue(skipped.contains("swagger-notes.json`: not an OpenAPI 3 or Swagger 2 document"), skipped);
    }

    @Test
    void openapiDirMustExist(@TempDir Path root) {
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"map", "--root", root.toString(), "--openapi-dir", root.resolve("nope").toString(),
                "--out", root.toString()}, new PrintStream(new ByteArrayOutputStream(), true), new PrintStream(e, true)));
        assertTrue(e.toString().contains("--openapi-dir must be an existing directory"), e.toString());
    }

    private static void openapiOnly(String id, String file, int line, String handler) {
        JsonNode node = nodes.get(id);
        assertNotNull(node, id + " in " + nodes.keySet());
        assertEquals("endpoint", node.get("kind").asText());
        assertEquals("openapi-only", node.get("source").asText());
        assertEquals(file, node.get("file").asText());
        assertEquals(handler, node.get("handler").asText());
        if (line > 0) {
            assertEquals(line, node.get("line").asInt());
        }
        assertTrue(section("OpenAPI vs code mismatches: only in OpenAPI").contains("- `" + id + "` (" + file + ":"), report);
    }

    private static void codeOnly(String id, String file) {
        assertNotNull(nodes.get(id), id);
        assertTrue(section("OpenAPI vs code mismatches: only in code").lines()
                .anyMatch(l -> l.startsWith("- `" + id + "` (") && l.endsWith("is not in `" + file + "`")), report);
    }

    private static String section(String title) {
        String s = report.substring(report.indexOf("## " + title + "\n"));
        return s.substring(0, s.indexOf("\n## ", 3));
    }

    private static void run(String... args) {
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args, new PrintStream(new ByteArrayOutputStream(), true), new PrintStream(e, true)), e.toString());
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
