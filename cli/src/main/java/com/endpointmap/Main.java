package com.endpointmap;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.endpoints.AnnotationEndpointDetector;
import com.endpointmap.endpoints.ConstantEvaluator;
import com.endpointmap.endpoints.Endpoint;
import com.endpointmap.graph.GraphWriter;
import com.endpointmap.outbound.FeignDetector;
import com.endpointmap.trace.FlowTracer;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.url.TargetMatcher;
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

public final class Main {

    static final String USAGE = """
            Usage:
              endpoint-map discover --root <dir> --profile <p> [--resolutions resolutions.yml]
              endpoint-map map --root <dir> --profile <p> [--resolutions resolutions.yml] --out <dir>
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
        try {
            return args[0].equals("map") ? map(opts, out, err) : discover(out);
        } catch (Exception e) {
            err.println("endpoint-map failed: " + e);
            return 1;
        }
    }

    /** Traces every endpoint's handler to its outbound calls and matches each call to its target nodes. */
    private static Map<String, Object> buildGraph(Workspace ws, String profile, AnnotationEndpointDetector detector,
                                                  List<Endpoint> endpoints) {
        Map<String, Map<String, String>> configs = new HashMap<>();
        ws.repos().forEach(r -> configs.put(r.name(), ConfigResolver.load(r.dir(), profile, new ArrayList<>())));
        Types types = new Types(ws);
        List<OutboundDetector> detectors = List.of(new FeignDetector(detector, new ConstantEvaluator(types)));
        FlowTracer tracer = new FlowTracer(ws, types, detectors);
        TargetMatcher matcher = new TargetMatcher(ws, configs, endpoints);
        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, Map<String, Object>> targets = new HashMap<>();
        for (Endpoint e : endpoints) {
            Repo repo = ws.repos().stream().filter(r -> r.name().equals(e.service())).findFirst().orElseThrow();
            for (FlowTracer.Found f : tracer.trace(e.decl(), repo, configs.get(repo.name()))) {
                for (TargetMatcher.Target t : matcher.match(f.call(), e.service())) {
                    Map<String, Object> edge = GraphWriter.ordered("from", e.id(), "to", t.id(), "kind", t.edgeKind(),
                            "client", f.call().client(), "via", f.via(), "file", f.file(), "line", f.line());
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
        return GraphWriter.build(ws, endpoints, edges, targets.values());
    }

    private static int discover(PrintStream out) {
        out.println("discover: not implemented yet");
        return 0;
    }

    private static int map(Map<String, String> opts, PrintStream out, PrintStream err) throws Exception {
        String outDir = opts.get("--out");
        if (outDir == null) {
            err.println("map needs --out <dir>");
            return 2;
        }
        Workspace ws = WorkspaceLoader.load(Path.of(opts.get("--root")).toAbsolutePath().normalize());
        String profile = opts.get("--profile");
        AnnotationEndpointDetector detector = new AnnotationEndpointDetector(ws);
        List<Endpoint> endpoints = detector.detect(profile);
        Map<String, Object> graph = buildGraph(ws, profile, detector, endpoints);
        Path dir = Files.createDirectories(Path.of(outDir));
        GraphWriter.write(graph, dir.resolve("graph.json"));
        MapHtmlWriter.write(GraphWriter.toJson(graph), dir.resolve("map.html"));
        List<?> nodes = (List<?>) graph.get("nodes");
        List<String> unresolved = nodes.stream().map(n -> (String) ((Map<?, ?>) n).get("id"))
                .filter(id -> id.startsWith("unresolved:")).toList();
        out.printf("%d repos, %d endpoints, %d edges -> %s%n", ws.repos().size(),
                nodes.stream().filter(n -> "endpoint".equals(((Map<?, ?>) n).get("kind"))).count(),
                ((List<?>) graph.get("edges")).size(), dir);
        if (!unresolved.isEmpty()) {
            out.printf("%d unresolved target(s):%n", unresolved.size());
            unresolved.forEach(id -> out.println("  " + id));
        }
        if (!ws.parseFailures().isEmpty()) {
            out.printf("%d file(s) could not be parsed and were skipped:%n", ws.parseFailures().size());
            ws.parseFailures().forEach(f -> out.println("  " + f.relPath() + ": " + f.error()));
        }
        detector.warnings().forEach(w -> out.println("warning: config file skipped: " + w));
        // shortcut: printed only, list collisions in discover-report.md once that exists
        for (Object n : nodes) {
            Map<?, ?> node = (Map<?, ?>) n;
            if (node.containsKey("alsoHandledBy")) {
                out.println("warning: " + node.get("id") + " is mapped by more than one handler: " + node.get("handler")
                        + ", " + node.get("alsoHandledBy"));
            }
        }
        return 0;
    }
}
