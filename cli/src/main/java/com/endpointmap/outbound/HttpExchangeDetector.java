package com.endpointmap.outbound;

import com.endpointmap.endpoints.AnnotationEndpointDetector;
import com.endpointmap.endpoints.AnnotationEndpointDetector.Mapping;
import com.endpointmap.endpoints.UrlPaths;
import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.url.UrlEvaluator;
import com.endpointmap.url.UrlEvaluator.Env;
import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A call to a method of a Spring HTTP interface ({@code @GetExchange}, {@code @HttpExchange(method = ..)}...): verb
 * and path from the method, prefix (or absolute URL) from the interface's {@code @HttpExchange}, base URL from the
 * client the {@code HttpServiceProxyFactory} that created the proxy wraps (see {@link UrlEvaluator#client}). An
 * interface a class of the calling service implements (a controller) with no proxy found is that local bean, not a
 * client: the tracer walks into it.
 */
public final class HttpExchangeDetector implements OutboundDetector {

    private final AnnotationEndpointDetector mappings;
    private final UrlEvaluator urls;
    /** Interface -> repos holding a class that implements it. */
    private final Map<TypeDeclaration<?>, List<Repo>> implementedIn = new IdentityHashMap<>();

    public HttpExchangeDetector(Workspace ws, Types types, AnnotationEndpointDetector mappings, UrlEvaluator urls) {
        this.mappings = mappings;
        this.urls = urls;
        // shortcut: direct "implements" only; an implementation through a superclass reads as no implementation
        ws.repos().forEach(r -> r.sources().forEach(sf -> sf.cu().findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> !c.isInterface())
                .forEach(c -> c.getImplementedTypes().forEach(t -> types.resolve(t)
                        .ifPresent(i -> implementedIn.computeIfAbsent(i, k -> new ArrayList<>()).add(r))))));
    }

    @Override
    public List<OutboundCall> detect(CallSite site) {
        List<OutboundCall> out = new ArrayList<>();
        if (!(site.call() instanceof MethodCallExpr call) || call.getScope().isEmpty()) {
            return out;
        }
        for (MethodDeclaration m : site.callees()) {
            Optional<ClassOrInterfaceDeclaration> owner = m.getParentNode().filter(ClassOrInterfaceDeclaration.class::isInstance)
                    .map(ClassOrInterfaceDeclaration.class::cast).filter(ClassOrInterfaceDeclaration::isInterface);
            Optional<Mapping> mapping = mappings.mappingOf(m);
            if (owner.isEmpty() || m.getBody().isPresent() || mapping.isEmpty()
                    || m.getAnnotations().stream().noneMatch(a -> a.getName().getIdentifier().endsWith("Exchange"))) {
                continue;
            }
            String base = urls.client(call.getScope().get(), Env.of(site)).text();
            if (base == null && implementedIn.getOrDefault(owner.get(), List.of()).contains(site.service())) {
                continue; // the service's own controller, injected
            }
            for (String prefix : mappings.typePrefixes(owner.get())) {
                for (String verb : mapping.get().verbs()) {
                    for (String path : mapping.get().paths()) {
                        // @HttpExchange("https://host/api") / ("${x.url}"): an absolute URL replaces the client's base
                        boolean absolute = prefix.contains("://") || prefix.startsWith("${");
                        String uri = !absolute ? UrlPaths.join(prefix, path)
                                : path.isBlank() ? prefix : prefix.replaceAll("/+$", "") + UrlPaths.join(path);
                        UrlEvaluator.Resolved r = UrlEvaluator.toCall(base, uri, site.config());
                        out.add(new OutboundCall("httpexchange", verb.equals("ANY") ? "GET" : verb, r.baseUrl(), r.path(),
                                r.key(), "\"" + r.raw() + "\"", null));
                    }
                }
            }
        }
        return out;
    }
}
