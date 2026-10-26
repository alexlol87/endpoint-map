package com.endpointmap.graph;

import com.endpointmap.endpoints.Endpoint;
import com.endpointmap.workspace.Workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the graph.json document (PLAN.md section 4). Output is sorted so runs are reproducible.
 * Endpoints sharing an id become one node; every other handler is listed in {@code alsoHandledBy}.
 */
public final class GraphWriter {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private GraphWriter() {
    }

    /**
     * {@code edges} are deduplicated and sorted; {@code targets} are the non-endpoint nodes they point to
     * (external, unresolved).
     */
    public static Map<String, Object> build(Workspace ws, List<Endpoint> endpoints, Collection<Map<String, Object>> edges,
                                            Collection<Map<String, Object>> targets) {
        List<Map<String, Object>> services = new ArrayList<>();
        for (int i = 0; i < ws.repos().size(); i++) {
            Workspace.Repo r = ws.repos().get(i);
            services.add(ordered("name", r.name(), "kind", r.kind().name(), "colorIndex", i));
        }
        Map<String, Map<String, Object>> nodes = new TreeMap<>();
        for (Endpoint e : endpoints) {
            Map<String, Object> node = nodes.get(e.id());
            if (node == null) {
                nodes.put(e.id(), ordered("id", e.id(), "kind", "endpoint", "service", e.service(),
                        "method", e.method(), "path", e.path(), "handler", e.handler(), "file", e.file(),
                        "line", e.line(), "source", e.source()));
            } else if (!(e.handler().equals(node.get("handler")) && e.file().equals(node.get("file")) && node.get("line").equals(e.line()))) {
                // same id from another handler (headers/params versioning, unresolved {*} paths): keep them all
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> also = (List<Map<String, Object>>) node.computeIfAbsent("alsoHandledBy", k -> new ArrayList<>());
                Map<String, Object> other = ordered("handler", e.handler(), "file", e.file(), "line", e.line());
                if (!also.contains(other)) {
                    also.add(other);
                }
            }
        }
        targets.forEach(t -> nodes.putIfAbsent((String) t.get("id"), t));
        List<Map<String, Object>> sortedEdges = new ArrayList<>(new LinkedHashSet<>(edges));
        sortedEdges.sort(Comparator.comparing((Map<String, Object> e) -> (String) e.get("from"))
                .thenComparing(e -> (String) e.get("to"))
                .thenComparing(e -> String.valueOf(e.get("via")))
                .thenComparing(e -> (String) e.get("file"))
                .thenComparing(e -> (Integer) e.get("line")));
        // flows are filled in by the caller (FlowTree), which needs the written edge order for its indexes
        return ordered("services", services, "nodes", new ArrayList<>(nodes.values()),
                "edges", sortedEdges, "flows", Map.of());
    }

    public static String toJson(Map<String, Object> graph) {
        try {
            return JSON.writeValueAsString(graph);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(Map<String, Object> graph, Path file) throws IOException {
        Files.writeString(file, toJson(graph) + "\n");
    }

    public static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
