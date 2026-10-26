package com.endpointmap.report;

import com.endpointmap.openapi.OpenApi;
import com.endpointmap.resolutions.Resolutions;
import com.endpointmap.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** discover-report.md: read-only, regenerated on every discover. Lists what was skipped or guessed, plus stats. */
public final class DiscoverReport {

    private DiscoverReport() {
    }

    public static void write(Path file, Workspace ws, Map<String, Object> graph, Collection<Resolutions.Found> asked,
                             Resolutions resolutions, List<String> configWarnings, List<String> traceWarnings,
                             List<String> routesNotUnderstood, OpenApi openapi,
                             Resolutions.Stats stats) throws IOException {
        List<Map<?, ?>> nodes = ((List<?>) graph.get("nodes")).stream().<Map<?, ?>>map(n -> (Map<?, ?>) n).toList();
        List<String> unresolved = unresolved(graph);
        long services = ws.repos().stream().filter(r -> r.kind() == Workspace.Kind.service).count();
        StringBuilder md = new StringBuilder("# endpoint-map discover report\n\n"
                + "Read-only: rewritten by every `discover`. Edit `resolutions.yml` instead.\n\n## Stats\n\n");
        md.append("- repos: ").append(ws.repos().size()).append(" (").append(services).append(" services, ")
                .append(ws.repos().size() - services).append(" libraries)\n")
                .append("- endpoints: ").append(nodes.stream().filter(n -> "endpoint".equals(n.get("kind"))).count()).append('\n')
                .append("- edges: ").append(((List<?>) graph.get("edges")).size()).append('\n')
                .append("- unresolved targets (with current resolutions): ").append(unresolved.size()).append('\n')
                .append("- files that failed to parse: ").append(ws.parseFailures().size()).append('\n')
                .append("- resolutions.yml: ").append(stats.entries()).append(" entries, ").append(stats.blank())
                .append(" blank, ").append(stats.added()).append(" new, ").append(stats.stale()).append(" stale, ")
                .append(stats.hostBlank()).append(" host: blank (kept external)\n");

        section(md, "Files that failed to parse (skipped)", ws.parseFailures().stream()
                .map(f -> "`" + f.relPath() + "`: " + f.error()).toList());
        section(md, "Config files that failed to load (skipped)", configWarnings);
        section(md, "Hosts treated as external by heuristic", asked.stream().filter(f -> f.key().startsWith("host:"))
                .map(f -> "`" + f.service() + "` -> `" + f.key().substring(5) + "`: "
                        + valueOrBlank(resolutions.value(f.service(), f.key())) + ". " + f.hint()).toList());
        section(md, "Unresolved targets", unresolved.stream().map(u -> "`" + u.replaceFirst(": ", "`: ")).toList());
        section(md, "Endpoints mapped by more than one handler", nodes.stream().filter(n -> n.containsKey("alsoHandledBy"))
                .map(n -> "`" + n.get("id") + "`: " + n.get("handler") + ", " + n.get("alsoHandledBy")).toList());
        section(md, "Tracing problems", traceWarnings);
        section(md, "OpenAPI vs code mismatches: only in OpenAPI", openapi.onlyInOpenApi());
        section(md, "OpenAPI vs code mismatches: only in code", openapi.onlyInCode());
        section(md, "External calls not in their OpenAPI", openapi.externalNotInSpec());
        section(md, "OpenAPI files skipped", openapi.problems());
        section(md, "Routes not understood", routesNotUnderstood.stream().map(r -> "`" + r.replaceFirst(": ", "`: ")).toList());
        Files.writeString(file, md);
    }

    /** "id: reason" of every unresolved node of the graph, in graph order. */
    public static List<String> unresolved(Map<String, Object> graph) {
        return ((List<?>) graph.get("nodes")).stream().map(n -> (Map<?, ?>) n)
                .filter(n -> ((String) n.get("id")).startsWith("unresolved:"))
                .map(n -> n.get("id") + ": " + n.get("reason")).toList();
    }

    private static String valueOrBlank(String v) {
        return v == null ? "blank in resolutions.yml, kept external" : "resolved as `" + v + "`";
    }

    private static void section(StringBuilder md, String title, List<String> lines) {
        md.append("\n## ").append(title).append("\n\n");
        if (lines.isEmpty()) {
            md.append("None.\n");
        }
        lines.forEach(l -> md.append("- ").append(l.replace("\n", " ")).append('\n'));
    }
}
