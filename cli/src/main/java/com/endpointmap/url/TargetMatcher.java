package com.endpointmap.url;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.endpoints.Endpoint;
import com.endpointmap.endpoints.UrlPaths;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an outbound call into target node ids. The host names a service when its first label is a service's repo
 * name or {@code spring.application.name}, or when it is localhost with that service's {@code server.port}; any other
 * host is external. Within the service, verb must match ({@code ANY} matches every verb) and the path is compared
 * segment by segment, template variables matching anything. Every matching endpoint is a target; none, or an
 * unresolved base URL, gives an {@code unresolved:} node.
 */
public final class TargetMatcher {

    /** {@code node} is null when the target is an existing endpoint node. */
    public record Target(String id, String edgeKind, Map<String, Object> node) {
    }

    private static final Pattern URL = Pattern.compile("^(?:([a-zA-Z][\\w+.-]*)://)?([^/:?#]+)(?::(\\d+))?([^?#]*).*$");
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "0.0.0.0");

    private final Map<String, String> serviceByName = new HashMap<>();
    private final Map<String, String> serviceByPort = new HashMap<>();
    private final Map<String, List<Endpoint>> endpointsByService = new HashMap<>();

    public TargetMatcher(Workspace ws, Map<String, Map<String, String>> configs, List<Endpoint> endpoints) {
        Set<String> sharedPorts = new HashSet<>();
        for (Repo r : ws.repos()) {
            if (r.kind() != Workspace.Kind.service) {
                continue;
            }
            Map<String, String> config = configs.getOrDefault(r.name(), Map.of());
            serviceByName.put(r.name(), r.name());
            String app = config.get("spring.application.name");
            if (app != null) {
                serviceByName.putIfAbsent(ConfigResolver.resolve(app, config), r.name());
            }
            String port = config.get("server.port");
            if (port != null && serviceByPort.put(ConfigResolver.resolve(port, config), r.name()) != null) {
                sharedPorts.add(ConfigResolver.resolve(port, config));
            }
        }
        sharedPorts.forEach(serviceByPort::remove); // two services on one port: can't tell them apart
        for (Endpoint e : endpoints) {
            endpointsByService.computeIfAbsent(e.service(), k -> new java.util.ArrayList<>()).add(e);
        }
    }

    public List<Target> match(OutboundCall call, String callerService) {
        String path = call.path().replaceAll("[?#].*$", "");
        if (call.baseUrl() == null) {
            return List.of(unresolved(callerService + ":" + call.unresolvedKey(), call.verb(), path, null, call.unresolvedKey()));
        }
        Matcher u = URL.matcher(call.baseUrl().trim());
        if (!u.matches()) {
            return List.of(unresolved(callerService + ":" + call.baseUrl(), call.verb(), path, null, call.baseUrl()));
        }
        String host = u.group(2);
        String port = u.group(3);
        String full = UrlPaths.join(u.group(4), path);
        String service = serviceByName.get(host.split("\\.")[0]);
        if (service == null && LOCAL.contains(host) && port != null) {
            service = serviceByPort.get(port);
        }
        if (service == null) {
            String origin = (u.group(1) == null ? "http" : u.group(1)) + "://" + host + (port == null ? "" : ":" + port);
            String id = "external:" + origin + " " + call.verb() + " " + full;
            return List.of(new Target(id, "external", node("id", id, "kind", "external", "host", origin,
                    "method", call.verb(), "path", full)));
        }
        List<Target> hits = endpointsByService.getOrDefault(service, List.of()).stream()
                .filter(e -> e.method().equals(call.verb()) || e.method().equals("ANY") || call.verb().equals("ANY"))
                .filter(e -> samePath(e.path(), full))
                .map(Endpoint::id).distinct().sorted()
                .map(id -> new Target(id, "http", null)).toList();
        return hits.isEmpty() ? List.of(unresolved(service, call.verb(), full, service, null)) : hits;
    }

    static boolean samePath(String template, String path) {
        String[] a = template.split("/", -1);
        String[] b = path.split("/", -1);
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!(a[i].equals(b[i]) || isVar(a[i]) || isVar(b[i]))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVar(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    private static Target unresolved(String what, String verb, String path, String service, String missing) {
        String id = "unresolved:" + what + " " + verb + " " + path;
        Map<String, Object> n = node("id", id, "kind", "unresolved", "method", verb, "path", path);
        if (service != null) {
            n.put("service", service);
            n.put("reason", "no matching endpoint");
        } else {
            n.put("missing", missing);
            n.put("reason", "base URL unknown");
        }
        return new Target(id, "http", n);
    }

    private static Map<String, Object> node(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
