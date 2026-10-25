package com.endpointmap;

import com.endpointmap.endpoints.AnnotationEndpointDetector;
import com.endpointmap.endpoints.Endpoint;
import com.endpointmap.graph.GraphWriter;
import com.endpointmap.viewer.MapHtmlWriter;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.WorkspaceLoader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        AnnotationEndpointDetector detector = new AnnotationEndpointDetector(ws);
        List<Endpoint> endpoints = detector.detect(opts.get("--profile"));
        Map<String, Object> graph = GraphWriter.build(ws, endpoints);
        Path dir = Files.createDirectories(Path.of(outDir));
        GraphWriter.write(graph, dir.resolve("graph.json"));
        MapHtmlWriter.write(GraphWriter.toJson(graph), dir.resolve("map.html"));
        out.printf("%d repos, %d endpoints -> %s%n", ws.repos().size(), ((List<?>) graph.get("nodes")).size(), dir);
        if (!ws.parseFailures().isEmpty()) {
            out.printf("%d file(s) could not be parsed and were skipped:%n", ws.parseFailures().size());
            ws.parseFailures().forEach(f -> out.println("  " + f.relPath() + ": " + f.error()));
        }
        detector.warnings().forEach(w -> out.println("warning: config file skipped: " + w));
        // shortcut: printed only, list collisions in discover-report.md once that exists
        for (Object n : (List<?>) graph.get("nodes")) {
            Map<?, ?> node = (Map<?, ?>) n;
            if (node.containsKey("alsoHandledBy")) {
                out.println("warning: " + node.get("id") + " is mapped by more than one handler: " + node.get("handler")
                        + ", " + node.get("alsoHandledBy"));
            }
        }
        return 0;
    }
}
