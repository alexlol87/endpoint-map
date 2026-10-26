package com.endpointmap.openapi;

import com.endpointmap.config.ConfigResolver;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAPI 3 and Swagger 2 files, found as {@code **}{@code /openapi*}, {@code api-docs*}, {@code swagger*}
 * (.json/.yaml/.yml) in every Java repo, plus every such file (any name) under {@code --openapi-dir}. A file's owner is
 * the service its server URL names, else the service its file name or {@code info.title} names, else the repo holding
 * it. Overlap with a service is how many operations its code endpoints match (verb and path, tried with and without
 * the server URL path and the service's context path), leaving out operations whose paths 2 or more services have
 * (health, info, actuator). The file documents its owner even at low overlap (flagged in the report), unless another
 * service has strictly more overlap and at least 3 or half of its operations, or the owner has endpoints and none of
 * them is in the file (a vendored client or partner spec). With an external server host the file is that external
 * API, unless its owner clears the 3-or-half bar. Otherwise it is not used (listed in the report). Paths are compared
 * segment by segment, template variables matching anything whatever their names.
 */
public final class OpenApi {

    record Operation(String method, String key, String path, String operationId, String summary, String file, int line) {
    }

    /** Exactly one of {@code service} (the workspace service it documents) and {@code host} (external host[:port]) is set. */
    record Spec(String file, String service, String host, List<Operation> ops) {
    }

    /** A loaded file before attribution: {@code host} null when its server URL has none (relative, or a variable). */
    private record Raw(String file, String repo, String owner, Set<String> hints, String url, String host, String prefix,
            List<Operation> ops) {
    }

    /** A service's best prefix for a file: {@code distinct} operations matched that no other service has, {@code raw} all matched. */
    private record Score(String service, String prefix, int distinct, int raw) {
    }

    private static final Pattern NAME = Pattern.compile("(openapi|api-docs|swagger)[^/]*\\.(json|ya?ml)");
    private static final Pattern ANY_SPEC = Pattern.compile("[^/]*\\.(json|ya?ml)");
    private static final Pattern TOOL_CONFIG = Pattern.compile("openapitools\\.json|swagger-config\\.(json|ya?ml)");
    private static final Set<String> OUTPUT_DIRS = Set.of("target", "build", "bin", "out", "node_modules");
    private static final Pattern ABSOLUTE = Pattern.compile("^([a-zA-Z{][^:/?#]*)://([^/?#]*)([^?#]*).*$");
    private static final Pattern VAR = Pattern.compile("\\{([^}]*)}");
    private static final List<String> VERBS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final TargetMatcher matcher;
    private final List<String> services;
    private final Map<String, String> basePaths = new HashMap<>();
    private final List<Raw> raws = new ArrayList<>();
    private final List<Spec> specs = new ArrayList<>();
    private final Map<String, Operation> onlyOps = new HashMap<>();
    private final List<String> used = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> onlyInOpenApi = new ArrayList<>();
    private final List<String> onlyInCode = new ArrayList<>();
    private final TreeSet<String> externalNotInSpec = new TreeSet<>();

    /** Auto-finds inside each repo (non-Java folders are not repos, so they are skipped), and reads {@code openapiDir} too. */
    public OpenApi(Workspace ws, String openapiDir, TargetMatcher matcher, Map<String, Map<String, String>> configs) {
        this.matcher = matcher;
        this.services = ws.repos().stream().map(Workspace.Repo::name).sorted().toList();
        configs.forEach((s, c) -> basePaths.put(s, ConfigResolver.basePath(c)));
        Map<Path, String> files = new LinkedHashMap<>(); // file -> repo holding it
        ws.repos().forEach(r -> find(r.dir(), NAME).forEach(f -> files.put(f, r.name())));
        Path dir = openapiDir == null ? null : Path.of(openapiDir).toAbsolutePath().normalize();
        if (dir != null) {
            find(dir, ANY_SPEC).forEach(f -> files.putIfAbsent(f, ws.repos().stream().filter(r -> f.startsWith(r.dir()))
                    .map(Workspace.Repo::name).findFirst().orElse(null)));
        }
        files.forEach((f, repo) -> load(ws, dir, f, repo));
    }

    private void load(Workspace ws, Path dir, Path file, String repo) {
        boolean inWs = file.startsWith(ws.root());
        String rel = (inWs ? ws.root().relativize(file).toString() : "openapi-dir:" + dir.relativize(file)).replace('\\', '/');
        String name = file.getFileName().toString().toLowerCase();
        boolean named = NAME.matcher(name).matches();
        String text;
        JsonNode doc;
        try {
            text = Files.readString(file);
            doc = (name.endsWith(".json") ? JSON : YAML).readTree(text);
        } catch (IOException | RuntimeException e) {
            if (named) {
                problems.add("`" + rel + "`: could not be read, skipped: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            }
            return;
        }
        if (doc == null || !(doc.has("openapi") || doc.has("swagger")) || !doc.path("paths").isObject()) {
            if (named && !TOOL_CONFIG.matcher(name).matches()) { // other json/yaml files of --openapi-dir: silently not specs
                problems.add("`" + rel + "`: not an OpenAPI 3 or Swagger 2 document (no openapi/swagger and paths), skipped");
            }
            return;
        }
        String url = doc.has("swagger") ? swaggerUrl(doc) : serverUrl(doc.path("servers").path(0));
        Matcher abs = ABSOLUTE.matcher(url);
        String host = null;
        String prefix = url.replaceAll("[?#].*", "");
        if (abs.matches()) {
            prefix = abs.group(3);
            host = abs.group(1).contains("{") || abs.group(2).contains("{") ? null : origin(abs.group(1), abs.group(2));
        }
        Set<String> hints = new TreeSet<>();
        String title = doc.path("info").path("title").asText("");
        List<String> segments = List.of(rel.replaceFirst("\\.[^./]*$", "").split("[/:]"));
        ws.repos().forEach(r -> {
            if (r.name().equals(title) || segments.contains(r.name())) {
                hints.add(r.name());
            }
        });
        // the service the file name names: the longest service name (without "-service") that is a whole-word run of the stem
        String stem = "-" + file.getFileName().toString().toLowerCase().replaceFirst("\\.[^.]*$", "").replaceAll("[^a-z0-9]+", "-") + "-";
        List<String> byName = ws.repos().stream().map(Workspace.Repo::name).filter(n -> n.equals(title)
                || stem.contains("-" + n.toLowerCase().replaceFirst("-service$", "") + "-")).toList();
        int longest = byName.stream().mapToInt(String::length).max().orElse(0);
        List<String> top = byName.stream().filter(n -> n.length() == longest).toList();
        String owner = top.size() == 1 ? top.get(0) : repo != null ? repo : hints.size() == 1 ? hints.iterator().next() : null;
        Map<String, Integer> lines = keyLines(text);
        List<Operation> ops = new ArrayList<>();
        doc.get("paths").fields().forEachRemaining(p -> {
            JsonNode item = p.getValue();
            if (item.has("$ref")) {
                String ref = item.get("$ref").asText();
                item = ref.startsWith("#/") ? doc.at(ref.substring(1)) : null;
                if (item == null || item.isMissingNode()) {
                    problems.add("`" + rel + "`: `" + p.getKey() + "`: $ref " + ref + " not followed (only refs inside the file"
                            + " are read), its operations are left out");
                    return;
                }
            }
            JsonNode it = item;
            VERBS.stream().filter(it::has).forEach(v -> ops.add(new Operation(v.toUpperCase(), p.getKey(), null,
                    text(it.get(v), "operationId"), text(it.get(v), "summary"), rel, lines.getOrDefault(p.getKey(), 1))));
        });
        raws.add(new Raw(rel, repo, owner, hints, url, host, prefix, ops));
    }

    /** {@code servers[0].url} with its variables set to their defaults. */
    private static String serverUrl(JsonNode server) {
        JsonNode vars = server.path("variables");
        return VAR.matcher(server.path("url").asText("")).replaceAll(m -> Matcher.quoteReplacement(
                vars.path(m.group(1)).has("default") ? vars.path(m.group(1)).get("default").asText() : m.group()));
    }

    private static String swaggerUrl(JsonNode doc) {
        String basePath = doc.path("basePath").asText("");
        String host = doc.path("host").asText("");
        return host.isEmpty() ? basePath : doc.path("schemes").path(0).asText("https") + "://" + host + basePath;
    }

    /** scheme://host[:port], lower case, the scheme's default port left out. */
    private static String origin(String scheme, String hostPort) {
        String s = scheme.toLowerCase();
        String h = hostPort.toLowerCase().replaceFirst(".*@", "");
        String dflt = s.equals("https") ? ":443" : s.equals("http") ? ":80" : null;
        return s + "://" + (dflt != null && h.endsWith(dflt) ? h.substring(0, h.length() - dflt.length()) : h);
    }

    private static String text(JsonNode op, String field) {
        return op.hasNonNull(field) ? op.get(field).asText() : null;
    }

    /** The first line each key is declared on (JSON {@code "key":} or YAML {@code key:} at the start of a line). */
    private static Map<String, Integer> keyLines(String text) {
        Map<String, Integer> out = new HashMap<>();
        int n = 0;
        for (String l : (Iterable<String>) text.lines()::iterator) {
            n++;
            String t = l.strip();
            char q = t.isEmpty() ? ' ' : t.charAt(0);
            int end = q == '"' || q == '\'' ? t.indexOf(q, 1) : -1;
            String key = end > 0 ? t.substring(1, end) : t.split(":(\\s|$)", 2)[0];
            out.putIfAbsent(key, n);
        }
        return out;
    }

    /**
     * Decides which service every file documents (see the class comment), then compares each service's OpenAPI
     * operations with its endpoints found in code; returns an {@code openapi-only} endpoint (no handler to trace) for
     * each operation code does not have. Services without OpenAPI are not compared.
     */
    public List<Endpoint> crossCheck(List<Endpoint> code) {
        Map<String, List<Endpoint>> byService = code.stream().collect(Collectors.groupingBy(Endpoint::service, TreeMap::new,
                Collectors.toList()));
        raws.forEach(r -> classify(r, byService));
        Map<String, Endpoint> added = new TreeMap<>();
        Map<String, Endpoint> codeOnly = new TreeMap<>();
        for (Spec s : specs) {
            if (s.service() == null) {
                continue;
            }
            List<Endpoint> mine = byService.getOrDefault(s.service(), List.of());
            for (Operation op : s.ops()) {
                if (mine.stream().noneMatch(e -> matches(e, op.method(), op.path()))) {
                    Endpoint e = new Endpoint(s.service(), op.method(), op.path(), "(OpenAPI only)", op.file(), op.line(),
                            "openapi-only", null);
                    if (added.putIfAbsent(e.id(), e) == null) {
                        onlyOps.put(e.id(), op);
                    }
                }
            }
            List<Operation> documented = specs.stream().filter(o -> s.service().equals(o.service()))
                    .flatMap(o -> o.ops().stream()).toList();
            mine.stream().filter(e -> documented.stream().noneMatch(op -> matches(e, op.method(), op.path())))
                    .forEach(e -> codeOnly.putIfAbsent(e.id(), e));
        }
        added.values().forEach(e -> onlyInOpenApi.add("`" + e.id() + "` (" + e.file() + ":" + e.line()
                + "), added to the map as an `openapi-only` endpoint"));
        codeOnly.values().forEach(e -> onlyInCode.add("`" + e.id() + "` (" + e.handler() + ", " + e.file() + ":" + e.line()
                + ") is not in " + String.join(", ", specs.stream().filter(s -> e.service().equals(s.service()))
                .map(s -> "`" + s.file() + "`").toList())));
        return List.copyOf(added.values());
    }

    private void classify(Raw r, Map<String, List<Endpoint>> code) {
        String named = r.host() == null ? null : matcher.service(r.host());
        boolean external = r.host() != null && matcher.isExternal(r.host());
        Map<String, List<String>> prefixes = new TreeMap<>();
        for (String s : services) {
            String base = basePaths.getOrDefault(s, "");
            prefixes.put(s, List.copyOf(new LinkedHashSet<>(List.of(r.prefix(), UrlPaths.join(base, r.prefix()), "", base))));
        }
        List<Operation> ops = r.ops();
        // operations matching code in 2+ services (health, info, actuator) say nothing about which one the file documents
        Set<Operation> shared = new java.util.HashSet<>();
        for (Operation op : ops) {
            if (services.stream().filter(s -> prefixes.get(s).stream().anyMatch(p -> hit(code.get(s), p, op))).count() > 1) {
                shared.add(op);
            }
        }
        Map<String, Score> scores = new TreeMap<>();
        Score best = null;
        for (String s : services) {
            Score top = null;
            for (String p : prefixes.get(s)) {
                List<Operation> hits = ops.stream().filter(op -> hit(code.get(s), p, op)).toList();
                Score sc = new Score(s, p, (int) hits.stream().filter(op -> !shared.contains(op)).count(), hits.size());
                if (top == null || sc.distinct() > top.distinct() || sc.distinct() == top.distinct() && sc.raw() > top.raw()) {
                    top = sc;
                }
            }
            String base = basePaths.getOrDefault(s, "");
            if (top.raw() == 0 && !r.prefix().startsWith(base)) { // nothing matches: the context path, then the server path
                top = new Score(s, UrlPaths.join(base, r.prefix()), 0, 0);
            }
            scores.put(s, top);
            if (top.distinct() > 0 && (best == null || top.distinct() > best.distinct()
                    || top.distinct() == best.distinct() && rank(r, named, s) < rank(r, named, best.service()))) {
                best = top;
            }
        }
        int total = ops.size();
        String dflt = named != null ? named : r.owner();
        Score own = dflt == null ? null : scores.get(dflt);
        // shortcut: an owner whose code has endpoints yet matches none of the file is taken for a vendored copy, even if
        // it is the owner's spec-first doc of routes the walker cannot read; upgrade with a reason from the user (resolutions)
        boolean disowned = own != null && own.raw() == 0 && code.containsKey(dflt);
        Score chosen;
        String why = "";
        if (external) {
            chosen = best != null && best.service().equals(r.owner()) && clears(best, total) ? best : null;
        } else if (best != null && clears(best, total) && (own == null || best.distinct() > own.distinct())) {
            chosen = best;
        } else if (own != null && !disowned) {
            chosen = own;
            why = clears(own, total) ? "" : "low overlap, " + own.distinct() + " of " + total + " operations match its code; attributed by "
                    + (dflt.equals(named) ? "its server URL" : dflt.equals(r.repo()) ? "location" : "name") + ", check";
        } else {
            chosen = null;
        }
        String counts = best == null ? "none of its " + total + " operations match only one service's code"
                : "best match " + best.service() + ", " + best.distinct() + " of " + total + " operations match its code";
        if (chosen != null) {
            String p = chosen.prefix();
            specs.add(new Spec(r.file(), chosen.service(), null, ops.stream().map(op -> new Operation(op.method(), op.key(),
                    UrlPaths.join(p, op.key()), op.operationId(), op.summary(), op.file(), op.line())).toList()));
            used.add("`" + r.file() + "`: documents " + chosen.service() + " (" + (why.isEmpty() ? chosen.distinct() + " of " + total
                    + " operations match its code" : why) + (p.isEmpty() || p.equals("/") ? "" : "; paths prefixed with `"
                    + UrlPaths.join(p) + "`") + ")");
        } else if (external) {
            specs.add(new Spec(r.file(), null, r.host(), ops.stream().map(op -> new Operation(op.method(), op.key(),
                    UrlPaths.join(r.prefix(), op.key()), op.operationId(), op.summary(), op.file(), op.line())).toList()));
            used.add("`" + r.file() + "`: external API of " + r.host() + " (its server host is not a workspace service"
                    + (best == null ? " and no service's code has its operations)" : "; " + counts + ")"));
        } else {
            problems.add("`" + r.file() + "`: not used: could not tell which service it documents (" + counts
                    + (best == null ? "" : "; at least 3 or half are needed")
                    + (disowned ? "; " + dflt + " has endpoints and none of them are in it" : "")
                    + (r.host() == null ? "" : ", server " + r.host()) + ")");
        }
    }

    /** Enough overlap to give a file to a service other than its owner: at least 3 operations or half of them. */
    private static boolean clears(Score s, int total) {
        return s.distinct() > 0 && (s.distinct() >= 3 || s.distinct() * 2 >= total);
    }

    private static boolean hit(List<Endpoint> code, String prefix, Operation op) {
        return code != null && code.stream().anyMatch(e -> matches(e, op.method(), UrlPaths.join(prefix, op.key())));
    }

    /** Tie-break among services matching equally many operations: lower wins. */
    private static int rank(Raw r, String named, String service) {
        return service == null ? 9 : service.equals(named) ? 0 : service.equals(r.owner()) ? 1 : r.hints().contains(service) ? 2 : 3;
    }

    private static boolean matches(Endpoint e, String method, String path) {
        return (e.method().equals(method) || e.method().equals("ANY")) && TargetMatcher.samePath(path, e.path());
    }

    /**
     * Gives an external node ({@code host} = scheme://host[:port], {@code method}, {@code path}) the operation of
     * its host's OpenAPI that it calls: {@code operation} (the path template), {@code operationId}, {@code summary},
     * {@code openapi} (the file); the operation with the fewest template segments wins. A call no operation matches
     * is listed in the report. An {@code openapi-only} endpoint node gets its {@code operationId} and {@code summary}.
     */
    public void annotate(Map<String, Object> node) {
        if ("openapi-only".equals(node.get("source"))) {
            Operation op = onlyOps.get(String.valueOf(node.get("id")));
            if (op != null) {
                putOperation(node, op);
            }
            return;
        }
        Matcher h = ABSOLUTE.matcher(String.valueOf(node.get("host")));
        if (!"external".equals(node.get("kind")) || !h.matches()) {
            return;
        }
        String origin = origin(h.group(1), h.group(2));
        List<Spec> mine = specs.stream().filter(s -> origin.equals(s.host())).toList();
        if (mine.isEmpty()) {
            return;
        }
        String verb = (String) node.get("method");
        String path = (String) node.get("path");
        Operation hit = mine.stream().flatMap(s -> s.ops().stream())
                .filter(op -> (op.method().equals(verb) || verb.equals("ANY")) && TargetMatcher.samePath(op.path(), path))
                .min(Comparator.comparingLong(op -> VAR.matcher(op.path()).results().count())).orElse(null);
        if (hit == null) {
            externalNotInSpec.add("`" + node.get("id") + "`: no operation of " + String.join(", ", mine.stream()
                    .map(s -> "`" + s.file() + "`").toList()) + " matches " + verb + " " + path);
            return;
        }
        node.put("operation", hit.path());
        putOperation(node, hit);
        node.put("openapi", hit.file());
    }

    private static void putOperation(Map<String, Object> node, Operation op) {
        if (op.operationId() != null) {
            node.put("operationId", op.operationId());
        }
        if (op.summary() != null) {
            node.put("summary", op.summary());
        }
    }

    /** How each file used was attributed: the service it documents, or the external API it describes. */
    public List<String> used() {
        return used;
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

    private static List<Path> find(Path dir, Pattern name) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> name.matcher(p.getFileName().toString().toLowerCase()).matches() && Files.isRegularFile(p))
                    .filter(p -> {
                        Path rel = dir.relativize(p);
                        for (int i = 0; i < rel.getNameCount() - 1; i++) {
                            if (OUTPUT_DIRS.contains(rel.getName(i).toString())) {
                                return false;
                            }
                        }
                        return !rel.toString().replace('\\', '/').contains("src/test/");
                    })
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
