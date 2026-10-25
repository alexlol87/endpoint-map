package com.endpointmap.graph;

import com.endpointmap.endpoints.Endpoint;
import com.endpointmap.workspace.Workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds the graph.json document (PLAN.md section 4). Output is sorted so runs are reproducible. */
public final class GraphWriter {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private GraphWriter() {
    }

    public static Map<String, Object> build(Workspace ws, List<Endpoint> endpoints) {
        List<Map<String, Object>> services = new ArrayList<>();
        for (int i = 0; i < ws.repos().size(); i++) {
            Workspace.Repo r = ws.repos().get(i);
            services.add(ordered("name", r.name(), "kind", r.kind().name(), "colorIndex", i));
        }
        Map<String, Map<String, Object>> nodes = new TreeMap<>();
        for (Endpoint e : endpoints) {
            nodes.putIfAbsent(e.id(), ordered("id", e.id(), "kind", "endpoint", "service", e.service(),
                    "method", e.method(), "path", e.path(), "handler", e.handler(), "file", e.file(),
                    "line", e.line(), "source", e.source()));
        }
        return ordered("services", services, "nodes", new ArrayList<>(nodes.values()),
                "edges", List.of(), "flows", Map.of());
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

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
