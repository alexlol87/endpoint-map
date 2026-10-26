package com.endpointmap;

import static com.endpointmap.FlowTreeTest.paths;
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

/** Black-box: polymorphic calls as oneOf, narrowed by @Qualifier/@Primary/bean name/@ConditionalOnProperty; low confidence (issue #15). */
class PolymorphismTest {

    @TempDir
    static Path dir;
    static JsonNode env;

    @BeforeAll
    static void map() throws Exception {
        String res = dir.resolve("resolutions.yml").toString();
        run("discover", RepositoryTablesTest.ENV, "--resolutions", res);
        run("map", RepositoryTablesTest.ENV, "--resolutions", res, "--out", dir.resolve("out").toString());
        env = new ObjectMapper().readTree(dir.resolve("out/graph.json").toFile());
    }

    private static Set<String> targets(JsonNode g, String from) {
        Set<String> out = new TreeSet<>();
        g.get("edges").forEach(e -> {
            if (e.get("from").asText().equals(from)) {
                out.add(e.get("to").asText());
            }
        });
        return out;
    }

    @Test
    void injectedInterfaceWithSeveralImplementationsIsOneOf() {
        // List<Notifier>.forEach: every implementation (a local @Primary one, two library ones built by @Bean methods)
        List<String> p = paths(env, "notification-service POST /notifications/broadcast");
        String pre = "loop allNotifiers.forEach > oneOf Notifier > ";
        assertTrue(p.contains(pre + "call EmailNotifier.notify > edge external:https://api.sendgrid.com POST /v3/mail/send"), p.toString());
        assertTrue(p.contains(pre + "call PushNotifier.notify > edge external:https://fcm.googleapis.com POST /v1/projects/acme/messages:send"), p.toString());
        assertTrue(p.contains(pre + "call SmsNotifier.notify > edge notification-service POST /notifications/sms"), p.toString());
    }

    @Test
    void qualifierAndPrimaryNarrowTheBranches() {
        // @Qualifier("sms") -> the @Bean(name = "sms") SmsNotifier; unqualified -> the @Primary PushNotifier; no oneOf left
        List<String> p = paths(env, "notification-service POST /notifications/{userId}");
        assertTrue(p.stream().noneMatch(s -> s.contains("oneOf") || s.contains("EmailNotifier")), p.toString());
        assertTrue(p.stream().filter(s -> s.contains("SmsNotifier")).allMatch(s -> s.startsWith("if ")), p.toString());
        assertTrue(p.stream().filter(s -> s.contains("PushNotifier")).allMatch(s -> s.startsWith("else ")), p.toString());
        assertFalse(targets(env, "notification-service POST /notifications/{userId}").contains("external:https://api.sendgrid.com POST /v3/mail/send"));
        // shipping: @Primary DhlCarrier vs @Qualifier("ups") UpsCarrier
        assertFalse(targets(env, "shipping-service POST /shipments").stream().anyMatch(t -> t.contains("ups.com")));
        assertFalse(targets(env, "shipping-service POST /shipments/express").stream().anyMatch(t -> t.contains("dhl.com")));
        // LabelPrinter: ZplLabelPrinter's @ConditionalOnProperty is off (labels.format unset), PdfLabelPrinter matches if missing
        assertTrue(paths(env, "shipping-service POST /shipments").contains("call PdfLabelPrinter.print > edge external:https://labels.example-print.com POST /pdf/{id}"));
        assertTrue(paths(env, "shipping-service POST /shipments").stream().noneMatch(s -> s.contains("oneOf")));
    }

    @Test
    void beanNameConditionsSingleImplementationAndNameFallback(@TempDir Path root) throws Exception {
        Path a = root.resolve("svc-a/src/main/java/a");
        write(a.resolve("App.java"), "package a;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(root.resolve("svc-a/src/main/resources/application.yml"), "send:\n  two: \"off\"\n");
        String imports = "import org.springframework.web.reactive.function.client.WebClient;\nimport reactor.core.publisher.Mono;\n";
        String call = "{ return WebClient.create(\"http://svc-b\").get().uri(\"/b/%s\").retrieve().bodyToMono(String.class); }";
        write(a.resolve("Sender.java"), "package a;\nimport reactor.core.publisher.Mono;\npublic interface Sender { Mono<String> send(); }\n");
        write(a.resolve("Solo.java"), "package a;\nimport reactor.core.publisher.Mono;\npublic interface Solo { Mono<String> send(); }\n");
        for (String[] c : List.of(new String[] {"OneSender", "@org.springframework.stereotype.Component(\"one\")", "Sender", "one"},
                new String[] {"TwoSender", "@org.springframework.stereotype.Component @org.springframework.boot.autoconfigure.condition."
                        + "ConditionalOnProperty(name = \"send.two\", havingValue = \"on\")", "Sender", "two"},
                new String[] {"ThreeSender", "@org.springframework.stereotype.Component", "Sender", "three"},
                new String[] {"SoloImpl", "@org.springframework.stereotype.Component", "Solo", "solo"})) {
            write(a.resolve(c[0] + ".java"), "package a;\n" + imports + c[1] + "\npublic class " + c[0] + " implements " + c[2]
                    + " {\n  public Mono<String> send() " + call.formatted(c[3]) + "\n}\n");
        }
        write(a.resolve("Overloaded.java"), "package a;\n" + imports + "@org.springframework.stereotype.Component\npublic class Overloaded {\n"
                + "  public Mono<String> go(String s) " + call.formatted("one") + "\n  public Mono<String> go(Integer i) " + call.formatted("two") + "\n}\n");
        write(a.resolve("Api.java"), """
                package a;
                import org.springframework.web.bind.annotation.GetMapping;
                import reactor.core.publisher.Mono;
                @org.springframework.web.bind.annotation.RestController
                public class Api {
                  private final Sender one;
                  private final Sender any;
                  private final Solo solo;
                  private final Overloaded ov;
                  public Api(Sender one, Sender any, Solo solo, Overloaded ov) { this.one = one; this.any = any; this.solo = solo; this.ov = ov; }
                  @GetMapping("/name") Mono<String> name() { return one.send(); }
                  @GetMapping("/any") Mono<String> any() { return any.send(); }
                  @GetMapping("/solo") Mono<String> solo() { return solo.send(); }
                  @GetMapping("/low") Mono<String> low() { return ov.go(x()); }
                  static String x() { return "x"; }
                }
                """);
        Path b = root.resolve("svc-b/src/main/java/b");
        write(b.resolve("App.java"), "package b;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        StringBuilder api = new StringBuilder("package b;\n@org.springframework.web.bind.annotation.RestController\npublic class Api {\n");
        for (String p : List.of("one", "two", "three", "solo")) {
            api.append("  @org.springframework.web.bind.annotation.GetMapping(\"/b/").append(p).append("\") public void ").append(p).append("() { }\n");
        }
        write(b.resolve("Api.java"), api.append("}\n").toString());
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        run("map", root, "--resolutions", res.toString(), "--out", root.resolve("out").toString());
        JsonNode g = new ObjectMapper().readTree(root.resolve("out/graph.json").toFile());

        // no qualifier, no primary: the bean named like the injection point
        assertEquals(Set.of("svc-b GET /b/one"), targets(g, "svc-a GET /name"));
        // nothing narrows: oneOf over the implementations whose condition holds (send.two is off)
        assertEquals(Set.of("svc-b GET /b/one", "svc-b GET /b/three"), targets(g, "svc-a GET /any"));
        assertEquals(List.of("oneOf Sender > call OneSender.send > edge svc-b GET /b/one", "oneOf Sender > call ThreeSender.send > edge svc-b GET /b/three"),
                paths(g, "svc-a GET /any"));
        // exactly one implementation: followed directly, no oneOf
        assertEquals(List.of("call SoloImpl.send > edge svc-b GET /b/solo"), paths(g, "svc-a GET /solo"));
        // the solver can't resolve ov.go(..) (Mono is outside the workspace) and two overloads fit by name: low confidence
        List<JsonNode> low = new ArrayList<>();
        g.get("edges").forEach(e -> {
            if (e.get("from").asText().equals("svc-a GET /low")) {
                low.add(e);
            }
        });
        assertEquals(2, low.size(), g.get("edges").toString());
        assertTrue(low.stream().allMatch(e -> e.path("confidence").asText().equals("low")), low.toString());
        g.get("edges").forEach(e -> assertTrue(e.get("from").asText().equals("svc-a GET /low") || !e.has("confidence"), e.toString()));
    }
}
