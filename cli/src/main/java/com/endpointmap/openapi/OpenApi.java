package com.endpointmap.openapi;

import com.endpointmap.endpoints.Endpoint;
import com.endpointmap.endpoints.UrlPaths;
import com.endpointmap.url.TargetMatcher;
import com.endpointmap.workspace.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OpenAPI 3 and Swagger 2 files, found as {@code **}{@code /openapi*}, {@code api-docs*}, {@code swagger*}
 * (.json/.yaml/.yml) in every Java repo, or under {@code --openapi-dir}. A file whose server URL names a host that is
 * not a workspace service describes an external API; any other file documents the service it belongs to, its paths
 * prefixed by the server URL path (OpenAPI 3 {@code servers[0]}, Swagger 2 {@code basePath}). Paths are compared
 * segment by segment, template variables matching anything whatever their names.
 */
public final class OpenApi {

    record Operation(String method, String path, String operationId, String summary, String file, int line) {
    }

    /** Exactly one of {@code service} (the workspace service it documents) and {@code host} (external host[:port]) is set. */
    record Spec(String file, String service, String host, List<Operation> ops) {
    }

    private static final Pattern NAME = Pattern.compile("(openapi|api-docs|swagger)[^/]*\\.(json|ya?ml)");
    private static final Pattern ABSOLUTE = Pattern.compile("^[a-zA-Z][\\w+.-]*://([^/?#]*)([^?#]*).*$");
    private static final List<String> VERBS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final List<Spec> specs = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> onlyInOpenApi = new ArrayList<>();
    private final List<String> onlyInCode = new ArrayList<>();
    private final TreeSet<String> externalNotInSpec = new TreeSet<>();

    /** {@code openapiDir} null: auto-find inside each repo (non-Java folders are not repos, so they are skipped). */
    public OpenApi(Workspace ws, String openapiDir, TargetMatcher matcher) {
        if (openapiDir == null) {
            ws.repos().forEach(r -> find(r.dir()).forEach(f -> load(ws, f, r.name(), matcher)));
        } else {
            find(Path.of(openapiDir).toAbsolutePath().normalize()).forEach(f -> load(ws, f, null, matcher));
        }
    }

    private void load(Workspace ws, Path file, String repo, TargetMatcher matcher) {
        String rel = file.startsWith(ws.root()) ? ws.root().relativize(file).toString().replace('\\', '/') : file.toString();
        String text;
        JsonNode doc;
        try {
            text = Files.readString(file);
            doc = (file.toString().endsWith(".json") ? JSON : YAML).readTree(text);
        } catch (IOException | RuntimeException e) {
            problems.add("`" + rel + "`: could not be read, skipped: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            return;
        }
        if (doc == null || !(doc.has("openapi") || doc.has("swagger")) || !doc.path("paths").isObject()) {
            problems.add("`" + rel + "`: not an OpenAPI 3 or Swagger 2 document (no openapi/swagger and paths), skipped");
            return;
        }
        String url = doc.has("swagger") ? swaggerUrl(doc) : doc.path("servers").path(0).path("url").asText("");
        Matcher abs = ABSOLUTE.matcher(url);
        boolean absolute = abs.matches();
        String prefix = absolute ? abs.group(2) : url;
        boolean external = absolute && matcher.isExternal(url);
        // shortcut: an auto-found internal file documents its own repo even if its server URL names another service, and a
        // context path missing from the server URL is not added; upgrade when a real workspace keeps such files
        String service = external ? null : repo;
        if (service == null && !external) {
            // --openapi-dir: the service its server URL names, else a repo named in its path or info.title
            service = absolute ? matcher.service(url) : null;
            String title = doc.path("info").path("title").asText("");
            service = service != null ? service : ws.repos().stream().map(Workspace.Repo::name)
                    .filter(n -> rel.contains(n) || title.equals(n)).max(Comparator.comparingInt(String::length)).orElse(null);
        }
        if (service == null && !external) {
            problems.add("`" + rel + "`: could not tell which service it documents (no service in its server URL, file"
                    + " path or info.title), skipped");
            return;
        }
        List<String> lines = text.lines().toList();
        List<Operation> ops = new ArrayList<>();
        doc.get("paths").fields().forEachRemaining(p -> VERBS.stream().filter(v -> p.getValue().has(v)).forEach(v -> {
            JsonNode op = p.getValue().get(v);
            ops.add(new Operation(v.toUpperCase(), UrlPaths.join(prefix, p.getKey()), text(op, "operationId"),
                    text(op, "summary"), rel, line(lines, p.getKey())));
        }));
        specs.add(new Spec(rel, service, external ? abs.group(1).toLowerCase() : null, ops));
    }

    private static String swaggerUrl(JsonNode doc) {
        String basePath = doc.path("basePath").asText("");
        String host = doc.path("host").asText("");
        return host.isEmpty() ? basePath : doc.path("schemes").path(0).asText("https") + "://" + host + basePath;
    }

    private static String text(JsonNode op, String field) {
        return op.hasNonNull(field) ? op.get(field).asText() : null;
    }

    /** First line that declares the path as a key; 1 when not found. */
    private static int line(List<String> lines, String path) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.startsWith("\"" + path + "\"") || l.startsWith("'" + path + "'") || l.startsWith(path + ":")) {
                return i + 1;
            }
        }
        return 1;
    }

    /**
     * Compares every service's OpenAPI operations with its endpoints found in code; returns an {@code openapi-only}
     * endpoint (no handler to trace) for each operation code does not have. Services without OpenAPI are not compared.
     */
    public List<Endpoint> crossCheck(List<Endpoint> code) {
        Map<String, Endpoint> added = new TreeMap<>();
        Map<String, Endpoint> codeOnly = new TreeMap<>();
        for (Spec s : specs) {
            if (s.service() == null) {
                continue;
            }
            List<Endpoint> mine = code.stream().filter(e -> e.service().equals(s.service())).toList();
            for (Operation op : s.ops()) {
                if (mine.stream().noneMatch(e -> matches(e, op))) {
                    added.putIfAbsent(s.service() + " " + op.method() + " " + op.path(), new Endpoint(s.service(), op.method(),
                            op.path(), op.operationId() != null ? op.operationId() : "(OpenAPI only)", op.file(), op.line(),
                            "openapi-only", null));
                }
            }
            List<Operation> documented = specs.stream().filter(o -> s.service().equals(o.service()))
                    .flatMap(o -> o.ops().stream()).toList();
            mine.stream().filter(e -> documented.stream().noneMatch(op -> matches(e, op))).forEach(e -> codeOnly.putIfAbsent(e.id(), e));
        }
        added.values().forEach(e -> onlyInOpenApi.add("`" + e.id() + "` (" + e.file() + ":" + e.line()
                + "), added to the map as an `openapi-only` endpoint"));
        codeOnly.values().forEach(e -> onlyInCode.add("`" + e.id() + "` (" + e.handler() + ", " + e.file() + ":" + e.line()
                + ") is not in " + String.join(", ", specs.stream().filter(s -> e.service().equals(s.service()))
                .map(s -> "`" + s.file() + "`").toList())));
        return List.copyOf(added.values());
    }

    private static boolean matches(Endpoint e, Operation op) {
        return (e.method().equals(op.method()) || e.method().equals("ANY")) && TargetMatcher.samePath(op.path(), e.path());
    }

    /**
     * Gives an external node ({@code host} = scheme://host[:port], {@code method}, {@code path}) the operation of
     * its host's OpenAPI that it calls: {@code operation} (the path template), {@code operationId}, {@code summary},
     * {@code openapi} (the file). A call no operation matches is listed in the report.
     */
    public void annotate(Map<String, Object> node) {
        Matcher h = ABSOLUTE.matcher(String.valueOf(node.get("host")));
        if (!"external".equals(node.get("kind")) || !h.matches()) {
            return;
        }
        List<Spec> mine = specs.stream().filter(s -> h.group(1).equalsIgnoreCase(s.host())).toList();
        if (mine.isEmpty()) {
            return;
        }
        String verb = (String) node.get("method");
        String path = (String) node.get("path");
        Operation hit = mine.stream().flatMap(s -> s.ops().stream())
                .filter(op -> (op.method().equals(verb) || verb.equals("ANY")) && TargetMatcher.samePath(op.path(), path))
                .findFirst().orElse(null);
        if (hit == null) {
            externalNotInSpec.add("`" + node.get("id") + "`: no operation of " + String.join(", ", mine.stream()
                    .map(s -> "`" + s.file() + "`").toList()) + " matches " + verb + " " + path);
            return;
        }
        node.put("operation", hit.path());
        if (hit.operationId() != null) {
            node.put("operationId", hit.operationId());
        }
        if (hit.summary() != null) {
            node.put("summary", hit.summary());
        }
        node.put("openapi", hit.file());
    }

    public List<String> problems() {
        return problems;
    }

    public List<String> onlyInOpenApi() {
        return onlyInOpenApi;
    }

    public List<String> onlyInCode() {
        return onlyInCode;
    }

    public List<String> externalNotInSpec() {
        return List.copyOf(externalNotInSpec);
    }

    private static List<Path> find(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> NAME.matcher(p.getFileName().toString().toLowerCase()).matches() && Files.isRegularFile(p))
                    .filter(p -> {
                        String rel = dir.relativize(p).toString().replace('\\', '/');
                        return !rel.startsWith("target/") && !rel.contains("/target/") && !rel.contains("src/test/")
                                && !rel.contains("node_modules/");
                    })
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
