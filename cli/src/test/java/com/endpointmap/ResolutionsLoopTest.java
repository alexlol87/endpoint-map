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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: discover writes resolutions.yml + discover-report.md, merges on re-run, and map applies it (issue #4). */
class ResolutionsLoopTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final String X = "svc-a GET /x";

    @Test
    void discoverTwiceWithAHandEditThenMap(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        feign(a, "Pricing", "name = \"pricing\"", "GetMapping(\"/prices/{sku}\")");
        feign(a, "Ship", "name = \"ship\", url = \"${SHIP_URL}\"", "PostMapping(\"/ship\")");
        feign(a, "Inv", "name = \"inv\", url = \"${inv.url}\"", "GetMapping(\"/inv\")");
        feign(a, "Ord", "name = \"ord\", url = \"https://svc-b.prod.acme.io\"", "PostMapping(\"/orders\")");
        feign(a, "Stripe", "name = \"stripe\", url = \"https://api.stripe.com/v1\"", "PostMapping(\"/charges\")");
        feign(a, "Odd", "name = \"odd\", url = com.vendor.Urls.ODD", "GetMapping(\"/odd\")"); // constant from a jar
        feign(a, "Fresh", "name = \"fresh\"", "GetMapping(\"/fresh\")");
        api(a, "inv.get();");
        write(root.resolve("svc-a/src/main/resources/application.yml"), "spring.application.name: svc-a\n");
        write(root.resolve("svc-a/src/main/resources/application-prod.yml"), "broken: [unclosed\n");
        write(a.resolve("Broken.java"), "package a;\npublic class Broken { void x( { }\n");
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(b.resolve("Api.java"), """
                package b;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Api {
                  @GetMapping("/prices/{sku}") public void price() { }
                  @PostMapping("/orders") public void order() { }
                }
                """);
        Path res = root.resolve("conf/resolutions.yml");

        String out1 = run("discover", root, "--resolutions", res.toString());
        JsonNode y = YAML.readTree(res.toFile());
        JsonNode props = y.get("svc-a").get("properties");
        assertEquals(new TreeSet<>(List.of("feign:pricing", "${SHIP_URL}", "inv.url", "host:svc-b.prod.acme.io",
                "host:api.stripe.com")), names(props));
        props.forEach(v -> assertEquals("", v.asText()));
        assertEquals(Set.of("Api.x:9"), names(y.get("svc-a").get("callSites")));
        String text = Files.readString(res);
        assertTrue(text.contains("    # used by Api.x (svc-a/src/main/java/a/Api.java:7)\n    # hint: @FeignClient(name = \"pricing\") has no url\n"
                + "    feign:pricing: \"\"\n"), text);
        assertTrue(text.contains("# hint: url = \"${SHIP_URL}\"; environment variable"), text);
        assertTrue(text.contains("# hint: found in code: https://svc-b.prod.acme.io; treated as external because the name"
                + " looks public; leave blank to keep it external, or write service:svc-b (its first label is that service)"), text);
        assertTrue(text.contains("# hint: url = com.vendor.Urls.ODD (not readable from code)"), text);
        String report = Files.readString(root.resolve("conf/discover-report.md"));
        assertTrue(report.contains("`svc-a/src/main/java/a/Broken.java`: (line"), report);
        assertTrue(report.contains("application-prod.yml"), report);
        assertTrue(report.contains("`svc-a` -> `svc-b.prod.acme.io`"), report);
        assertTrue(report.contains("- unresolved targets (with current resolutions): 4"), report);
        assertTrue(out1.contains("4 unresolved"), out1);
        assertTrue(out1.contains("resolutions: 6 entries, 4 blank, 6 new, 0 stale, 2 host: blank (kept external)"), out1);

        Graph before = map(root, null, root.resolve("out0"));
        assertEquals(Set.of("unresolved:svc-a:feign:pricing GET /prices/{sku}", "unresolved:svc-a:${SHIP_URL} POST /ship",
                "unresolved:svc-a:${inv.url} GET /inv", "external:https://svc-b.prod.acme.io POST /orders",
                "external:https://api.stripe.com POST /v1/charges", "unresolved:svc-a:Api.x:9 GET /odd"), before.targetsOf(X));

        // hand edit; then the code drops the Inv call and gains a Fresh one
        Files.writeString(res, text.replace("feign:pricing: \"\"", "feign:pricing: service:svc-b")
                .replace("host:svc-b.prod.acme.io: \"\"", "host:svc-b.prod.acme.io: \"service:svc-b\"")
                .replace("\"${SHIP_URL}\": \"\"", "\"${SHIP_URL}\": external:https://ship.example.com/api")
                .replace("Api.x:9: \"\"", "Api.x:9: \"external:https://odd.example.com\"")
                .replace("inv.url: \"\"", "inv.url: service:svc-b"));
        api(a, "fresh.get();");
        String out2 = run("discover", root, "--resolutions", res.toString());
        assertTrue(out2.contains("resolutions: 7 entries, 1 blank, 1 new, 1 stale, 1 host: blank (kept external)"), out2);
        String text2 = Files.readString(res);
        assertTrue(text2.contains("    inv.url: service:svc-b  # stale: no longer found in code\n"), text2);
        assertTrue(text2.contains("    feign:fresh: \"\"\n"), text2);
        JsonNode p2 = YAML.readTree(res.toFile()).get("svc-a").get("properties");
        assertEquals("service:svc-b", p2.get("feign:pricing").asText());
        assertEquals("service:svc-b", p2.get("host:svc-b.prod.acme.io").asText());
        assertEquals("external:https://ship.example.com/api", p2.get("${SHIP_URL}").asText());
        assertEquals("", p2.get("host:api.stripe.com").asText());
        assertEquals("external:https://odd.example.com", YAML.readTree(res.toFile()).get("svc-a").get("callSites").get("Api.x:9").asText());
        run("discover", root, "--resolutions", res.toString());
        assertEquals(text2, Files.readString(res), "a re-run without changes rewrites the same file");

        Graph after = map(root, res, root.resolve("out1"));
        assertEquals(Set.of("svc-b GET /prices/{sku}", "external:https://ship.example.com POST /api/ship",
                "svc-b POST /orders", "external:https://api.stripe.com POST /v1/charges",
                "external:https://odd.example.com GET /odd", "unresolved:svc-a:feign:fresh GET /fresh"), after.targetsOf(X));
        assertEquals("http", after.edge(X, "svc-b POST /orders").get("kind").asText());
        assertEquals("external", after.node("external:https://ship.example.com POST /api/ship").get("kind").asText());
        assertEquals("unresolved", after.node("unresolved:svc-a:feign:fresh GET /fresh").get("kind").asText());
        assertFalse(after.nodes.toString().contains("svc-b.prod.acme.io"));
        assertTrue(after.stdout.contains("1 unresolved"), after.stdout);
    }

    @Test
    void badValuesAreRefusedAndTheFileIsLeftAlone(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        feign(a, "Pricing", "name = \"pricing\"", "GetMapping(\"/p\")");
        api(a, "");
        Path res = root.resolve("resolutions.yml");
        String edited = "svc-a:\n  properties:\n    feign:pricing: svc-b   # forgot the service: prefix\n";
        Files.writeString(res, edited);
        assertEquals(1, Main.run(new String[] {"discover", "--root", root.toString(), "--profile", "prod", "--resolutions",
                res.toString()}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        assertEquals(edited, Files.readString(res));

        Files.writeString(res, "svc-a:\n  properties:\n    feign:pricing: service:nope\n");
        Graph g = map(root, res, root.resolve("out"));
        JsonNode n = g.node("unresolved:svc-a:feign:pricing GET /p");
        assertTrue(n.get("reason").asText().contains("nope"), n.toString());
    }

    @Test
    void edgeViaIsTheShortestPathNotOneThroughACycle(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        feign(a, "Cl", "name = \"c\", url = \"https://api.example.com\"", "GetMapping(\"/c\")");
        write(a.resolve("Api.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  A first; B second;
                  @org.springframework.web.bind.annotation.GetMapping("/1") public void one() { first.a(); }
                  @org.springframework.web.bind.annotation.GetMapping("/2") public void two() { second.b(0); }
                }
                """);
        write(a.resolve("A.java"), "package a;\npublic class A { B b; public void a() { b.b(1); } }\n");
        write(a.resolve("B.java"), "package a;\npublic class B { A a; Cl c; public void b(int n) { if (n > 0) a.a(); c.get(); } }\n");
        Graph g = map(root, null, root.resolve("out"));
        // /1 memoizes A.a -> B.b -> Cl.get; /2 must not reuse it as B.b -> A.a -> B.b -> Cl.get
        assertEquals(List.of("B.b", "Cl.get"), strings(g.edge("svc-a GET /2", "external:https://api.example.com GET /c").get("via")));
    }

    @Test
    void placeholderWithSuffixIsKeyedByThePlaceholderAndKeepsTheSuffix(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        feign(a, "L1", "name = \"l1\", url = \"${LOY_HOST}/loyalty-api\"", "GetMapping(\"/tier\")");
        feign(a, "L2", "name = \"l2\", url = \"${LOY_HOST}/points-api\"", "GetMapping(\"/points\")");
        feign(a, "P", "name = \"p\", url = \"${b.url}/v2\"", "GetMapping(\"/items/{id}\")");
        feign(a, "E", "name = \"e\", url = \"\"", "GetMapping(\"/e\")");
        feign(a, "D", "name = \"d\", url = \"${d.url:}\"", "GetMapping(\"/d\")");
        feign(a, "H", "name = \"h\", url = \"${h.url}\"", "GetMapping(\"/h\")");
        write(a.resolve("Api.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  L1 l1; L2 l2; P p; E e; D d; H h;
                  @org.springframework.web.bind.annotation.GetMapping("/x")
                  public void x() {
                    l1.get();
                    l2.get();
                    p.get(); e.get(); d.get(); h.get();
                  }
                }
                """);
        write(root.resolve("svc-a/src/main/resources/application-prod.yml"), "h.url: https://svc-b.prod.acme.io\n");
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(b.resolve("Api.java"), "package b;\n@org.springframework.web.bind.annotation.RestController\npublic class Api {\n"
                + "  @org.springframework.web.bind.annotation.GetMapping(\"/v2/items/{id}\") public void item() { }\n}\n");
        Path res = root.resolve("resolutions.yml");

        String out = run("discover", root, "--resolutions", res.toString());
        JsonNode props = YAML.readTree(res.toFile()).get("svc-a").get("properties");
        assertEquals(new TreeSet<>(List.of("${LOY_HOST}", "b.url", "feign:e", "feign:d", "host:svc-b.prod.acme.io")),
                names(props), out);
        String text = Files.readString(res);
        // two clients share ${LOY_HOST}: the hint lists both URL expressions
        assertTrue(text.contains("# hint: url = \"${LOY_HOST}/loyalty-api\", url = \"${LOY_HOST}/points-api\"; environment"
                + " variable ${LOY_HOST} not set"), text);
        assertTrue(text.contains("# hint: url = \"${b.url}/v2\"; property ${b.url} not set"), text);
        assertTrue(text.contains("# hint: @FeignClient(name = \"e\") has no url (url = \"\" is blank)"), text);
        assertTrue(text.contains("# hint: found in code: h.url = https://svc-b.prod.acme.io (application-prod.yml); treated as"
                + " external because the name looks public; leave blank to keep it external"), text);
        assertTrue(text.contains("    # used by Api.x (svc-a/src/main/java/a/Api.java:7)\n    # used by Api.x"
                + " (svc-a/src/main/java/a/Api.java:8)\n    # hint: url = \"${LOY_HOST}/loyalty-api\""), "one entry for both clients\n" + text);
        Graph before = map(root, res, root.resolve("out0"));
        assertTrue(before.targetsOf(X).contains("unresolved:svc-a:${LOY_HOST} GET /loyalty-api/tier"), before.targetsOf(X).toString());

        Files.writeString(res, text.replace("\"${LOY_HOST}\": \"\"", "\"${LOY_HOST}\": external:https://points.example.com")
                .replace("b.url: \"\"", "b.url: service:svc-b"));
        Graph after = map(root, res, root.resolve("out1"));
        assertEquals(Set.of("external:https://points.example.com GET /loyalty-api/tier",
                "external:https://points.example.com GET /points-api/points", "svc-b GET /v2/items/{id}",
                "unresolved:svc-a:feign:e GET /e", "unresolved:svc-a:feign:d GET /d",
                "external:https://svc-b.prod.acme.io GET /h"), after.targetsOf(X));
    }

    @Test
    void duplicateKeysAndBadValuesAreRefusedByName(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        feign(a, "Pricing", "name = \"pricing\"", "GetMapping(\"/p\")");
        api(a, "");
        Path res = root.resolve("resolutions.yml");
        String dup = "svc-a:\n  properties:\n    feign:pricing: service:svc-b\n    feign:pricing: \"\"\n";
        Files.writeString(res, dup);
        String[] r = runRaw(root, "discover", "--resolutions", res.toString());
        assertEquals("1", r[0]);
        assertTrue(r[2].contains("line 4: duplicate key 'feign:pricing'"), r[2]);
        assertEquals(dup, Files.readString(res));
        String blocks = "svc-a:\n  properties:\n    feign:pricing: service:svc-b\nsvc-a:\n  callSites: {}\n";
        Files.writeString(res, blocks);
        r = runRaw(root, "map", "--resolutions", res.toString(), "--out", root.resolve("out").toString());
        assertEquals("1", r[0]);
        assertTrue(r[2].contains("duplicate key 'svc-a'"), r[2]);
        Files.writeString(res, "svc-a:\n  properties:\n    feign:pricing: \"svc-b\"\n");
        r = runRaw(root, "discover", "--resolutions", res.toString());
        assertTrue(r[2].contains("feign:pricing: \"svc-b\" must be"), r[2]);
    }

    @Test
    void userCommentsSurviveRediscovery(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        feign(a, "Pricing", "name = \"pricing\"", "GetMapping(\"/p\")");
        feign(a, "Fresh", "name = \"fresh\"", "GetMapping(\"/f\")");
        api(a, "fresh.get();");
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        String text = Files.readString(res);
        String edited = text.replace("svc-a:\n", "# team notes for the whole file\n\n# svc-a is owned by payments\nsvc-a:  # since 2024\n")
                .replace("    # used by Api.x (svc-a/src/main/java/a/Api.java:7)\n",
                        "    # confirmed with platform team\n    # used by Api.x (svc-a/src/main/java/a/Api.java:7)\n")
                .replace("feign:pricing: \"\"", "feign:pricing: service:svc-b   # asked Bob")
                + "# end notes\n";
        assertTrue(edited.contains("# asked Bob") && edited.contains("# confirmed"), edited);
        Files.writeString(res, edited);
        run("discover", root, "--resolutions", res.toString());
        String once = Files.readString(res);
        for (String note : List.of("# team notes for the whole file\n# svc-a is owned by payments\nsvc-a:  # since 2024\n",
                "    # confirmed with platform team\n    # used by Api.x (svc-a/src/main/java/a/Api.java:7)\n",
                "    feign:pricing: service:svc-b  # asked Bob\n", "# end notes\n")) {
            assertTrue(once.contains(note), note + " missing from\n" + once);
        }
        run("discover", root, "--resolutions", res.toString());
        assertEquals(once, Files.readString(res), "comments round-trip");

        Files.delete(a.resolve("Pricing.java")); // the call is gone: the entry turns stale and keeps its notes
        run("discover", root, "--resolutions", res.toString());
        String stale = Files.readString(res);
        assertTrue(stale.contains("    # confirmed with platform team\n    feign:pricing: service:svc-b  # asked Bob  # stale: no"
                + " longer found in code\n"), stale);
        run("discover", root, "--resolutions", res.toString());
        assertEquals(stale, Files.readString(res));
        try (var files = Files.list(root)) {
            assertEquals(List.of(), files.filter(f -> f.toString().endsWith(".tmp")).toList(), "no temp file left");
        }
    }

    @Test
    void unresolvedTargetsSayWhy(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        feign(a, "Pricing", "name = \"pricing\"", "GetMapping(\"/prices/{sku}\")");
        feign(a, "Fresh", "name = \"fresh\"", "GetMapping(\"/f\")");
        feign(a, "Ord", "name = \"ord\"", "GetMapping(\"/none\")");
        api(a, "fresh.get(); ord.get();");
        write(root.resolve("svc-b/src/main/java/b/App.java"),
                "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        Path res = root.resolve("resolutions.yml");
        Files.writeString(res, "svc-a:\n  properties:\n    feign:pricing: service:svc-bb\n    feign:ord: service:svc-b\n");
        String out = run("discover", root, "--resolutions", res.toString());
        String typo = "unresolved:svc-a:feign:pricing GET /prices/{sku}: resolutions.yml names svc-bb for feign:pricing, which is"
                + " not a service in the workspace (did you mean svc-b?)";
        String blank = "unresolved:svc-a:feign:fresh GET /f: base URL unknown: resolutions.yml has no value for feign:fresh (svc-a)";
        String none = "unresolved:svc-b GET /none: no endpoint of svc-b matches GET /none";
        String report = Files.readString(root.resolve("discover-report.md"));
        for (String line : List.of(typo, blank, none)) {
            assertTrue(out.contains("  " + line + "\n"), line + " missing from\n" + out);
            assertTrue(report.contains("- `" + line.replaceFirst(": ", "`: ") + "\n"), line + " missing from\n" + report);
        }
    }

    @Test
    void mapDefaultsToTheResolutionsFileDiscoverWrote(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        feign(a, "Pricing", "name = \"pricing\"", "GetMapping(\"/p\")");
        api(a, "");
        Path cwd = Files.createDirectories(root.resolve("work"));
        String miss = process(cwd, "map", "--root", root.toString(), "--profile", "prod", "--out", "out0");
        assertTrue(miss.contains("resolutions: none ("), miss);
        process(cwd, "discover", "--root", root.toString(), "--profile", "prod");
        Path res = cwd.resolve("resolutions.yml");
        Files.writeString(res, Files.readString(res).replace("feign:pricing: \"\"", "feign:pricing: external:https://p.example.com"));
        String used = process(cwd, "map", "--root", root.toString(), "--profile", "prod", "--out", "out");
        assertTrue(used.contains("resolutions <- " + res.toRealPath()), used);
        assertTrue(Files.readString(cwd.resolve("out/graph.json")).contains("\"external:https://p.example.com GET /p\""));
    }

    // --- helpers ---

    /** Api.x calls the clients one per line from line 7 on: Pricing:7 Ship:8 Odd:9 Ord:10 Stripe:11, then {@code extra}. */
    private static void api(Path a, String extra) throws Exception {
        write(a.resolve("Api.java"), """
                package a;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  Pricing pricing; Ship ship; Inv inv; Ord ord; Stripe stripe; Odd odd; Fresh fresh;
                  @org.springframework.web.bind.annotation.GetMapping("/x")
                  public void x() {
                    pricing.get();
                    ship.get();
                    odd.get();
                    ord.get();
                    stripe.get();
                    %s
                  }
                }
                """.formatted(extra));
    }

    private static void feign(Path dir, String name, String attrs, String mapping) throws Exception {
        write(dir.resolve(name + ".java"), "package a;\n@org.springframework.cloud.openfeign.FeignClient(" + attrs + ")\npublic interface "
                + name + " { @org.springframework.web.bind.annotation." + mapping + " void get(); }\n");
    }

    record Graph(List<JsonNode> edges, List<JsonNode> nodes, String stdout) {

        Set<String> targetsOf(String from) {
            Set<String> out = new TreeSet<>();
            edges.stream().filter(e -> e.get("from").asText().equals(from)).forEach(e -> out.add(e.get("to").asText()));
            return out;
        }

        JsonNode edge(String from, String to) {
            return edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals(to))
                    .findFirst().orElseThrow(() -> new AssertionError("no edge " + from + " -> " + to + " in " + targetsOf(from)));
        }

        JsonNode node(String id) {
            return nodes.stream().filter(n -> n.get("id").asText().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError("missing node " + id));
        }
    }

    private static Graph map(Path root, Path resolutions, Path outDir) throws Exception {
        String stdout = resolutions == null ? run("map", root, "--out", outDir.toString())
                : run("map", root, "--resolutions", resolutions.toString(), "--out", outDir.toString());
        JsonNode g = JSON.readTree(outDir.resolve("graph.json").toFile());
        List<JsonNode> edges = new ArrayList<>();
        g.get("edges").forEach(edges::add);
        List<JsonNode> nodes = new ArrayList<>();
        g.get("nodes").forEach(nodes::add);
        return new Graph(edges, nodes, stdout);
    }

    /** {exit code, stdout, stderr}. */
    private static String[] runRaw(Path root, String command, String... more) {
        List<String> args = new ArrayList<>(List.of(command, "--root", root.toString(), "--profile", "prod"));
        args.addAll(List.of(more));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Main.run(args.toArray(String[]::new), new PrintStream(o, true), new PrintStream(e, true));
        return new String[] {String.valueOf(code), o.toString(), e.toString()};
    }

    /** Runs the CLI as a separate JVM in {@code cwd}, for defaults relative to the working directory; returns stdout. */
    private static String process(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-cp",
                System.getProperty("java.class.path"), Main.class.getName()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    private static String run(String command, Path root, String... more) {
        List<String> args = new ArrayList<>(List.of(command, "--root", root.toString(), "--profile", "prod"));
        args.addAll(List.of(more));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Main.run(args.toArray(String[]::new), new PrintStream(o, true), new PrintStream(e, true));
        assertEquals(0, code, e.toString());
        return o.toString();
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
