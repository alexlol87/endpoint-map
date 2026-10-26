package com.endpointmap;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.endpoints.AnnotationEndpointDetector;
import com.endpointmap.endpoints.ConstantEvaluator;
import com.endpointmap.endpoints.Endpoint;
import com.endpointmap.endpoints.RouterEndpointDetector;
import com.endpointmap.graph.GraphWriter;
import com.endpointmap.openapi.OpenApi;
import com.endpointmap.outbound.FeignDetector;
import com.endpointmap.outbound.HttpExchangeDetector;
import com.endpointmap.outbound.RepositoryDetector;
import com.endpointmap.outbound.RestTemplateDetector;
import com.endpointmap.outbound.WebClientDetector;
import com.endpointmap.report.DiscoverReport;
import com.endpointmap.resolutions.Resolutions;
import com.endpointmap.trace.FlowTracer;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.url.TargetMatcher;
import com.endpointmap.url.UrlEvaluator;
import com.endpointmap.viewer.MapHtmlWriter;
import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.endpointmap.workspace.WorkspaceLoader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Main {

    static final String USAGE = """
            Usage:
              endpoint-map discover --root <dir> --profile <p> [--resolutions resolutions.yml] [--openapi-dir <dir>]
              endpoint-map map --root <dir> --profile <p> [--resolutions resolutions.yml] [--openapi-dir <dir>] --out <dir>
            discover writes resolutions.yml (edit it) and discover-report.md next to it; map applies it.
            """;
    private static final Set<String> OPTIONS = Set.of("--root", "--profile", "--resolutions", "--out", "--openapi-dir");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Entry point used by tests; returns the process exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !List.of("discover", "map").contains(args[0])) {
            err.print(USAGE);
            return 2;
        }
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!OPTIONS.contains(args[i]) || i + 1 >= args.length) {
                err.println("Unknown or incomplete option: " + args[i]);
                err.print(USAGE);
                return 2;
            }
            opts.put(args[i], args[++i]);
        }
        String root = opts.get("--root");
        if (root == null || !Files.isDirectory(Path.of(root))) {
            err.println("--root must be an existing directory");
            return 2;
        }
        if (opts.containsKey("--openapi-dir") && !Files.isDirectory(Path.of(opts.get("--openapi-dir")))) {
            err.println("--openapi-dir must be an existing directory");
            return 2;
        }
        try {
            return args[0].equals("map") ? map(opts, out, err) : discover(opts, out);
        } catch (Exception e) {
            err.println("endpoint-map failed: " + e);
            return 1;
        }
    }

    /** Everything one run found: the graph, and every key resolutions.yml is asked about. */
    private record Scan(Workspace ws, Map<String, Object> graph, Map<String, Resolutions.Found> asked,
                        List<String> configWarnings, List<String> traceWarnings, List<String> routesNotUnderstood,
                        OpenApi openapi) {

        /** "id: reason" of every unresolved node. */
        List<String> unresolved() {
            return DiscoverReport.unresolved(graph);
        }
    }

    /** Traces every endpoint's handler to its outbound calls and matches each call to its target nodes. */
    private static Scan scan(Map<String, String> opts, Resolutions resolutions) {
        Workspace ws = WorkspaceLoader.load(Path.of(opts.get("--root")).toAbsolutePath().normalize());
        String profile = opts.get("--profile");
        AnnotationEndpointDetector detector = new AnnotationEndpointDetector(ws);
        List<Endpoint> endpoints = new ArrayList<>(detector.detect(profile));
        Map<String, Map<String, String>> configs = new HashMap<>();
        Map<String, Map<String, String>> sources = new HashMap<>();
        ws.repos().forEach(r -> configs.put(r.name(), ConfigResolver.load(r.dir(), profile, new ArrayList<>(),
                sources.computeIfAbsent(r.name(), k -> new HashMap<>()))));
        Types types = new Types(ws);
        ConstantEvaluator constants = new ConstantEvaluator(types);
        RouterEndpointDetector routes = new RouterEndpointDetector(ws, types, constants);
        endpoints.addAll(routes.detect(profile));
        UrlEvaluator urls = new UrlEvaluator(ws, types, constants);
        List<OutboundDetector> detectors = List.of(new FeignDetector(detector, constants), new WebClientDetector(urls),
                new RestTemplateDetector(urls), new HttpExchangeDetector(ws, types, detector, urls), new RepositoryDetector(ws, types, constants));
        FlowTracer tracer = new FlowTracer(ws, types, detectors, urls);
        TargetMatcher matcher = new TargetMatcher(ws, configs, sources, endpoints, resolutions);
        OpenApi openapi = new OpenApi(ws, opts.get("--openapi-dir"), matcher, configs);
        List<Endpoint> openapiOnly = openapi.crossCheck(endpoints);
        endpoints.addAll(openapiOnly);
        matcher.addEndpoints(openapiOnly); // calls to an endpoint only OpenAPI knows still reach it
        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, Map<String, Object>> targets = new HashMap<>();
        Map<String, Resolutions.Found> asked = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        for (Endpoint e : endpoints) {
            Repo repo = ws.repos().stream().filter(r -> r.name().equals(e.service())).findFirst().orElseThrow();
            List<FlowTracer.Found> found;
            if (e.decl() == null) { // openapi-only, or a route handler the walker couldn't read (listed in the report)
                continue;
            }
            if (tracer.bodiless(e.decl(), repo)) {
                warnings.add("tracing " + e.id() + ": its handler " + e.handler() + " has no body and no implementation was found, not traced");
                continue;
            }
            try {
                found = tracer.trace(e.decl(), e.controller(), repo, configs.get(repo.name()));
            } catch (OutOfMemoryError | StackOverflowError err) { // one pathological endpoint must not lose the whole map
                warnings.add("tracing " + e.id() + " failed (" + err.getClass().getSimpleName() + "), its edges are missing");
                continue;
            }
            new TreeSet<>(tracer.partialClusters()).forEach(c -> warnings.add("tracing " + e.id() + ": the recursive cluster at " + c
                    + " has too many paths, only part of them were followed (some of its edges may be missing)"));
            for (FlowTracer.Found f : found) {
                // the method holding the call: the one entered before the client method, or the handler itself
                String at = f.via().size() > 1 ? f.via().get(f.via().size() - 2) : e.handler();
                String callSite = at + ":" + f.line();
                if (f.call().op() != null) { // a database call: its table node is the target, nothing to resolve
                    // no table: an entity type variable nothing binds; the node says why (listed in the report)
                    String id = f.call().table() != null ? "db:" + e.service() + ":" + f.call().table()
                            : "unresolved:db " + e.service() + " " + f.call().label();
                    targets.putIfAbsent(id, f.call().table() != null
                            ? GraphWriter.ordered("id", id, "kind", "db", "tech", f.call().client(), "table", f.call().table())
                            : GraphWriter.ordered("id", id, "kind", "unresolved", "service", e.service(), "tech", f.call().client(),
                                    "reason", f.call().source()));
                    Map<String, Object> edge = GraphWriter.ordered("from", e.id(), "to", id, "kind", "db", "client", f.call().client(),
                            "op", f.call().op(), "repoMethod", f.call().label(), "via", f.via(), "file", f.file(), "line", f.line());
                    if (f.lowConfidence()) {
                        edge.put("confidence", "low");
                    }
                    edges.add(edge);
                    continue;
                }
                TargetMatcher.Ask ask = matcher.ask(f.call(), e.service(), callSite);
                if (ask != null) {
                    String section = ask.key().equals(callSite) ? Resolutions.CALL_SITES : Resolutions.PROPERTIES;
                    Resolutions.Found entry = asked.computeIfAbsent(e.service() + "\0" + ask.key(),
                            k -> new Resolutions.Found(e.service(), section, ask.key(), new TreeSet<>(), new TreeSet<>()));
                    entry.usages().add(at + " (" + f.file() + ":" + f.line() + ")");
                    if (ask.hint() != null) {
                        entry.hints().add(ask.hint());
                    }
                }
                for (TargetMatcher.Target t : matcher.match(f.call(), e.service(), callSite)) {
                    Map<String, Object> edge = GraphWriter.ordered("from", e.id(), "to", t.id(), "kind", t.edgeKind(),
                            "client", f.wrapper() != null ? f.wrapper() : f.call().client(), "transport", f.call().client(),
                            "via", f.via(), "file", f.file(), "line", f.line());
                    if (f.lowConfidence()) {
                        edge.put("confidence", "low");
                    }
                    edges.add(edge);
                    if (t.node() != null) {
                        targets.putIfAbsent(t.id(), t.node());
                    }
                }
            }
        }
        Map<String, Object> graph = GraphWriter.build(ws, endpoints, edges, targets.values());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        nodes.forEach(openapi::annotate);
        return new Scan(ws, graph, asked, detector.warnings(), warnings, routes.notUnderstood(), openapi);
    }

    private static int discover(Map<String, String> opts, PrintStream out) throws Exception {
        Path file = Path.of(opts.getOrDefault("--resolutions", "resolutions.yml")).toAbsolutePath();
        Resolutions old = Resolutions.read(file); // read first: a file we can't parse is never overwritten
        Scan scan = scan(opts, old);
        Files.createDirectories(file.getParent());
        Resolutions.Stats stats = old.write(file, scan.asked().values());
        Path report = file.resolveSibling("discover-report.md");
        DiscoverReport.write(report, scan.ws(), scan.graph(), scan.asked().values(), old, scan.configWarnings(),
                scan.traceWarnings(), scan.routesNotUnderstood(), scan.openapi(), stats);
        summary(scan, out);
        out.printf("resolutions: %d entries, %d blank, %d new, %d stale, %d host: blank (kept external) -> %s%n",
                stats.entries(), stats.blank(), stats.added(), stats.stale(), stats.hostBlank(), file);
        out.println("report -> " + report);
        return 0;
    }

    private static int map(Map<String, String> opts, PrintStream out, PrintStream err) throws Exception {
        String outDir = opts.get("--out");
        if (outDir == null) {
            err.println("map needs --out <dir>");
            return 2;
        }
        String file = opts.get("--resolutions");
        if (file != null && !Files.isRegularFile(Path.of(file))) {
            err.println("--resolutions " + file + " does not exist; run discover first");
            return 2;
        }
        // same default as discover, so map applies the file discover just wrote
        Path res = Path.of(file == null ? "resolutions.yml" : file).toAbsolutePath();
        boolean use = Files.isRegularFile(res);
        Scan scan = scan(opts, use ? Resolutions.read(res) : Resolutions.empty());
        Path dir = Files.createDirectories(Path.of(outDir));
        GraphWriter.write(scan.graph(), dir.resolve("graph.json"));
        MapHtmlWriter.write(GraphWriter.toJson(scan.graph()), dir.resolve("map.html"));
        out.println(use ? "resolutions <- " + res : "resolutions: none (" + res + " not found; run discover to create it)");
        out.println("map -> " + dir);
        summary(scan, out);
        return 0;
    }

    private static void summary(Scan scan, PrintStream out) {
        Map<String, Object> graph = scan.graph();
        List<?> nodes = (List<?>) graph.get("nodes");
        List<String> unresolved = scan.unresolved();
        out.printf("%d repos, %d endpoints, %d edges, %d unresolved%n", scan.ws().repos().size(),
                nodes.stream().filter(n -> "endpoint".equals(((Map<?, ?>) n).get("kind"))).count(),
                ((List<?>) graph.get("edges")).size(), unresolved.size());
        if (!unresolved.isEmpty()) {
            out.printf("%d unresolved target(s):%n", unresolved.size());
            unresolved.forEach(id -> out.println("  " + id));
        }
        if (!scan.ws().parseFailures().isEmpty()) {
            out.printf("%d file(s) could not be parsed and were skipped:%n", scan.ws().parseFailures().size());
            scan.ws().parseFailures().forEach(f -> out.println("  " + f.relPath() + ": " + f.error()));
        }
        scan.configWarnings().forEach(w -> out.println("warning: config file skipped: " + w));
        scan.traceWarnings().forEach(w -> out.println("warning: " + w));
        scan.routesNotUnderstood().forEach(w -> out.println("warning: route not understood: " + w));
        for (Object n : nodes) {
            Map<?, ?> node = (Map<?, ?>) n;
            if (node.containsKey("alsoHandledBy")) {
                out.println("warning: " + node.get("id") + " is mapped by more than one handler: " + node.get("handler")
                        + ", " + node.get("alsoHandledBy"));
            }
        }
    }
}
