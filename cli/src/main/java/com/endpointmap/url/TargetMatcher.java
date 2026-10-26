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
    private static final Pattern FIRST_PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)");

    private final Set<String> services = new HashSet<>();
    private final Resolutions resolutions;
    private final Map<String, Map<String, String>> configs;
    private final Map<String, Map<String, String>> configSources;

    /** {@code configSources}: per service, property -> the config file name that set it (for hints). */
    public TargetMatcher(Workspace ws, Map<String, Map<String, String>> configs,
                         Map<String, Map<String, String>> configSources, List<Endpoint> endpoints, Resolutions resolutions) {
        this.resolutions = resolutions;
        this.configs = configs;
        this.configSources = configSources;
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
        addEndpoints(endpoints);
    }

    /** More match targets, such as the {@code openapi-only} endpoints found after construction. */
    public void addEndpoints(List<Endpoint> endpoints) {
        for (Endpoint e : endpoints) {
            endpointsByService.computeIfAbsent(e.service(), k -> new java.util.ArrayList<>()).add(e);
        }
    }

    /** The service an absolute URL names (by host or localhost port), null when none. */
    public String service(String url) {
        Matcher u = URL.matcher(url.trim());
        return u.matches() ? service(u) : null;
    }

    /** True when an absolute URL names no service and is not localhost: the same rule outbound calls go by. */
    public boolean isExternal(String url) {
        Matcher u = URL.matcher(url.trim());
        return u.matches() && !LOCAL.contains(u.group(2)) && service(u) == null;
    }

    /**
     * The resolutions.yml entry for a call made at {@code callSite} ("Class.method:line"), null when code alone names
     * the target: {@code feign:<name>} or the property / {@code ${ENV}} placeholder of an unknown base URL, the call
     * site when the URL is not known at all, {@code host:<host[:port]>} when the host was called external by heuristic.
     * The hint quotes the URL expression from code.
     */
    public Ask ask(OutboundCall call, String callerService, String callSite) {
        String code = call.source() == null ? null : "url = " + call.source();
        if (call.baseUrl() == null) {
            if (call.unresolvedKey() == null) {
                return new Ask(callSite, code == null ? "the URL is not known from code" : code + " (not readable from code)");
            }
            String key = call.unresolvedKey();
            if (key.startsWith("feign:")) {
                return new Ask(key, "@FeignClient(name = \"" + key.substring(6) + "\") has no url"
                        + (code == null ? "" : " (" + code + " is blank)"));
            }
            // a property is named bare (pricing.base-url), an environment variable as written (${PAYMENT_HOST})
            Matcher p = PLACEHOLDER.matcher(key);
            boolean env = p.matches() && p.group(1).matches("[A-Z][A-Z0-9_]*");
            return new Ask(p.matches() && !env ? p.group(1) : key, code + "; " + (env ? "environment variable"
                    : p.matches() ? "property" : "placeholder") + " " + key + " not set in application.yml or the profile file");
        }
        Matcher u = URL.matcher(call.baseUrl().trim());
        if (!u.matches()) {
            return new Ask(callSite, code + " gives \"" + call.baseUrl() + "\", which is not a URL");
        }
        if (service(u) != null) {
            return null;
        }
        String host = u.group(2);
        String like = serviceByName.get(host.split("\\.")[0]);
        return new Ask("host:" + host + (u.group(3) == null ? "" : ":" + u.group(3)), "found in code: "
                + foundIn(call, callerService) + "; treated as external because the name looks public; leave blank to keep"
                + " it external, or write service:" + (like == null ? "<repo-name>" : like + " (its first label is that service)"));
    }

    /** "loyalty.base-url = https://x (application-prod.yml)" when the url comes from a property, else the url. */
    private String foundIn(OutboundCall call, String service) {
        Matcher p = call.source() == null ? null : FIRST_PLACEHOLDER.matcher(call.source());
        String file = p != null && p.find() ? configSources.getOrDefault(service, Map.of()).get(p.group(1)) : null;
        return file == null ? call.baseUrl() : p.group(1) + " = "
                + ConfigResolver.resolve(configs.get(service).get(p.group(1)), configs.get(service)) + " (" + file + ")";
    }

    public List<Target> match(OutboundCall call, String callerService, String callSite) {
        String path = call.path().replaceAll("[?#].*$", "");
        Ask ask = ask(call, callerService, callSite);
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
                String near = services.stream().filter(s -> distance(s, service) <= 2)
                        .min(java.util.Comparator.comparingInt((String s) -> distance(s, service)).thenComparing(s -> s))
                        .orElse(null);
                return List.of(unresolved(callerService + ":" + ask.key(), call.verb(), full, null, ask.key(),
                        "resolutions.yml names " + service + " for " + ask.key() + ", which is not a service in the workspace"
                                + (near == null ? "" : " (did you mean " + near + "?)")));
            }
            return endpoints(service, call.verb(), full);
        }
        String blank = ask == null ? null : "resolutions.yml has no value for " + ask.key() + " (" + callerService + ")";
        if (call.baseUrl() == null) {
            String missing = call.unresolvedKey() == null ? ask.key() : call.unresolvedKey();
            return List.of(unresolved(callerService + ":" + missing, call.verb(), path, null, missing,
                    "base URL unknown: " + blank));
        }
        if (!parsed) {
            return List.of(unresolved(callerService + ":" + call.baseUrl(), call.verb(), path, null, call.baseUrl(),
                    "base URL \"" + call.baseUrl() + "\" is not a URL: " + blank));
        }
        String service = service(u);
        if (service == null) {
            return List.of(external((u.group(1) == null ? "http" : u.group(1)) + "://" + u.group(2)
                    + (u.group(3) == null ? "" : ":" + u.group(3)), call.verb(), full));
        }
        return endpoints(service, call.verb(), full);
    }

    /** Levenshtein distance, for "did you mean". */
    static int distance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            row[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int diag = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int up = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), diag + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                diag = up;
            }
        }
        return row[b.length()];
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
        return hits.isEmpty() ? List.of(unresolved(service, verb, full, service, null,
                "no endpoint of " + service + " matches " + verb + " " + full)) : hits;
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

    public static boolean samePath(String template, String path) {
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

    private static Target unresolved(String what, String verb, String path, String service, String missing, String reason) {
        String id = "unresolved:" + what + " " + verb + " " + path;
        Map<String, Object> n = node("id", id, "kind", "unresolved", "method", verb, "path", path);
        if (service != null) {
            n.put("service", service);
        } else {
            n.put("missing", missing);
        }
        n.put("reason", reason);
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
