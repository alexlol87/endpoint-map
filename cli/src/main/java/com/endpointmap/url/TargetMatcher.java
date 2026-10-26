package com.endpointmap.url;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.endpoints.Endpoint;
import com.endpointmap.endpoints.UrlPaths;
import com.endpointmap.resolutions.Resolutions;
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
 * Turns an outbound call into target node ids. The host names a service when it looks internal (no dots, or a private
 * suffix such as {@code .internal} or {@code .svc.cluster.local}) and its first label is a service's repo name or
 * {@code spring.application.name}, or when it is localhost with that service's {@code server.port}; any other
 * host is external. Within the service, verb must match ({@code ANY} matches every verb) and the path is compared
 * segment by segment, template variables matching anything. Every matching endpoint is a target; none, or an
 * unresolved base URL, gives an {@code unresolved:} node. A value in resolutions.yml for the call's key (see
 * {@link #ask}) wins over all of this.
 */
public final class TargetMatcher {

    /** {@code node} is null when the target is an existing endpoint node. */
    public record Target(String id, String edgeKind, Map<String, Object> node) {
    }

    /** What resolutions.yml is asked about a call: its key there and what code showed. */
    public record Ask(String key, String hint) {
    }

    private static final Pattern URL = Pattern.compile("^(?:([a-zA-Z][\\w+.-]*)://)?([^/:?#]+)(?::(\\d+))?([^?#]*).*$");
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "0.0.0.0");
    private static final Set<String> INTERNAL_SUFFIXES = Set.of("local", "internal", "svc", "lan", "corp", "intranet");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)}");

    private final Map<String, String> serviceByName = new HashMap<>();
    private final Map<String, String> serviceByPort = new HashMap<>();
    private final Map<String, List<Endpoint>> endpointsByService = new HashMap<>();
    private final Set<String> services = new HashSet<>();
    private final Resolutions resolutions;

    public TargetMatcher(Workspace ws, Map<String, Map<String, String>> configs, List<Endpoint> endpoints,
                         Resolutions resolutions) {
        this.resolutions = resolutions;
        Set<String> sharedPorts = new HashSet<>();
        for (Repo r : ws.repos()) {
            if (r.kind() != Workspace.Kind.service) {
                continue;
            }
            Map<String, String> config = configs.getOrDefault(r.name(), Map.of());
            serviceByName.put(r.name(), r.name());
            services.add(r.name());
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

    /**
     * The resolutions.yml entry for a call made at {@code callSite} ("Class.method:line"), null when code alone names
     * the target: {@code feign:<name>} or the property / {@code ${ENV}} placeholder of an unknown base URL, the call
     * site when the URL is not known at all, {@code host:<host[:port]>} when the host was called external by heuristic.
     */
    public Ask ask(OutboundCall call, String callSite) {
        if (call.baseUrl() == null) {
            if (call.unresolvedKey() == null) {
                return new Ask(callSite, "the URL is not known from code");
            }
            String key = call.unresolvedKey();
            Matcher p = PLACEHOLDER.matcher(key);
            if (key.startsWith("feign:")) {
                return new Ask(key, "@FeignClient(name = \"" + key.substring(6) + "\") has no url");
            }
            // a property is named bare (pricing.base-url), an environment variable as written (${PAYMENT_HOST})
            boolean env = p.matches() && p.group(1).matches("[A-Z0-9_]+");
            return new Ask(p.matches() && !env ? p.group(1) : key, "url = \"" + key + "\"; "
                    + (env ? "environment variable" : "property") + " not set in application.yml or the profile file");
        }
        Matcher u = URL.matcher(call.baseUrl().trim());
        if (!u.matches()) {
            return new Ask(callSite, "base URL \"" + call.baseUrl() + "\" is not a URL");
        }
        if (service(u) != null) {
            return null;
        }
        String host = u.group(2);
        String like = serviceByName.get(host.split("\\.")[0]);
        return new Ask("host:" + host + (u.group(3) == null ? "" : ":" + u.group(3)), "found in code: " + call.baseUrl()
                + "; treated as external because the name looks public"
                + (like == null ? "" : "; its first label is the service " + like + " (write service:" + like + "?)"));
    }

    public List<Target> match(OutboundCall call, String callerService, String callSite) {
        String path = call.path().replaceAll("[?#].*$", "");
        Ask ask = ask(call, callSite);
        Matcher u = call.baseUrl() == null ? null : URL.matcher(call.baseUrl().trim());
        boolean parsed = u != null && u.matches();
        String full = parsed ? UrlPaths.join(u.group(4), path) : path;
        String resolved = ask == null ? null : resolutions.value(callerService, ask.key());
        if (resolved != null) {
            if (resolved.startsWith("external:")) {
                Matcher v = URL.matcher(resolved.substring("external:".length()));
                v.matches(); // Resolutions.read accepted only external:http(s)://host...
                return List.of(external(v.group(1) + "://" + v.group(2) + (v.group(3) == null ? "" : ":" + v.group(3)),
                        call.verb(), UrlPaths.join(v.group(4), full)));
            }
            String service = resolved.substring("service:".length());
            if (!services.contains(service)) {
                Target t = unresolved(callerService + ":" + ask.key(), call.verb(), full, null, ask.key());
                t.node().put("reason", "resolutions.yml names " + service + ", which is not a service in the workspace");
                return List.of(t);
            }
            return endpoints(service, call.verb(), full);
        }
        if (call.baseUrl() == null) {
            String missing = call.unresolvedKey() == null ? ask.key() : call.unresolvedKey();
            return List.of(unresolved(callerService + ":" + missing, call.verb(), path, null, missing));
        }
        if (!parsed) {
            return List.of(unresolved(callerService + ":" + call.baseUrl(), call.verb(), path, null, call.baseUrl()));
        }
        String service = service(u);
        if (service == null) {
            return List.of(external((u.group(1) == null ? "http" : u.group(1)) + "://" + u.group(2)
                    + (u.group(3) == null ? "" : ":" + u.group(3)), call.verb(), full));
        }
        return endpoints(service, call.verb(), full);
    }

    /** The service a parsed base URL names by host or localhost port, null when it looks external. */
    private String service(Matcher u) {
        String host = u.group(2);
        String service = looksInternal(host) ? serviceByName.get(host.split("\\.")[0]) : null;
        return service == null && LOCAL.contains(host) && u.group(3) != null ? serviceByPort.get(u.group(3)) : service;
    }

    private List<Target> endpoints(String service, String verb, String full) {
        List<Target> hits = endpointsByService.getOrDefault(service, List.of()).stream()
                .filter(e -> e.method().equals(verb) || e.method().equals("ANY") || verb.equals("ANY"))
                .filter(e -> samePath(e.path(), full))
                .map(Endpoint::id).distinct().sorted()
                .map(id -> new Target(id, "http", null)).toList();
        return hits.isEmpty() ? List.of(unresolved(service, verb, full, service, null)) : hits;
    }

    private static Target external(String origin, String verb, String full) {
        String id = "external:" + origin + " " + verb + " " + full;
        return new Target(id, "external", node("id", id, "kind", "external", "host", origin, "method", verb, "path", full));
    }

    /** A bare name (k8s service, docker-compose) or a private DNS name; billing-service.stripe.com is not ours. */
    // k8s "name.namespace" short names and internal DNS like order-service.prod.acme.io look public: discover asks
    // about every such host (host: entries), so the user maps them in resolutions.yml
    static boolean looksInternal(String host) {
        return !host.contains(".") || INTERNAL_SUFFIXES.contains(host.substring(host.lastIndexOf('.') + 1));
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
