package com.endpointmap.outbound;

import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.url.UrlEvaluator;
import com.endpointmap.url.UrlEvaluator.Env;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code restTemplate.getForObject(url, ..)} and the other RestTemplate methods: the verb from the method name, or
 * from the {@code HttpMethod} second argument of {@code exchange}/{@code execute}. The URL is the first argument, its
 * {@code {id}} template variables kept; the base is the template's root URI ({@code RestTemplateBuilder.rootUri},
 * {@code DefaultUriBuilderFactory}) when its bean sets one, else empty (see {@link UrlEvaluator#client}).
 */
public final class RestTemplateDetector implements OutboundDetector {

    private static final Map<String, String> VERBS = Map.ofEntries(Map.entry("getForObject", "GET"),
            Map.entry("getForEntity", "GET"), Map.entry("headForHeaders", "HEAD"), Map.entry("postForObject", "POST"),
            Map.entry("postForEntity", "POST"), Map.entry("postForLocation", "POST"), Map.entry("put", "PUT"),
            Map.entry("patchForObject", "PATCH"), Map.entry("delete", "DELETE"), Map.entry("optionsForAllow", "OPTIONS"),
            Map.entry("exchange", ""), Map.entry("execute", ""));

    private final UrlEvaluator urls;

    public RestTemplateDetector(UrlEvaluator urls) {
        this.urls = urls;
    }

    @Override
    public List<OutboundCall> detect(CallSite site) {
        if (!(site.call() instanceof MethodCallExpr call) || !VERBS.containsKey(call.getNameAsString())
                || call.getScope().isEmpty() || call.getArguments().isEmpty()
                || VERBS.get(call.getNameAsString()).isEmpty() && call.getArguments().size() < 2
                || !urls.clientType(call.getScope().get()).filter("RestTemplate"::equals).isPresent()) {
            return List.of();
        }
        Expression client = call.getScope().get();
        Env env = Env.of(site);
        String fixed = VERBS.get(call.getNameAsString());
        if (call.getNameAsString().equals("exchange") && call.getArguments().size() == 2) {
            // shortcut: exchange(RequestEntity, type) (the only 2-argument exchange) is a callSites entry; its verb is
            // read from RequestEntity.post(..)/method(m, ..) when written inline, its URL is not
            return List.of(new OutboundCall("resttemplate", requestEntityVerb(call.getArgument(0), env), null,
                    UrlEvaluator.ANY, null, call.getArgument(0).toString(), "RestTemplate.exchange"));
        }
        return urls.each(env, e -> {
            String verb = fixed.isEmpty() ? urls.verb(call.getArgument(1), e) : fixed;
            String base = urls.client(client, e).text();
            String path = urls.string(call.getArgument(0), e).text();
            UrlEvaluator.Resolved r = UrlEvaluator.toCall(base, path, site.config());
            String source = path.startsWith(UrlEvaluator.ANY) ? call.getArgument(0).toString() : "\"" + r.raw() + "\"";
            return new OutboundCall("resttemplate", verb, r.baseUrl(), r.path(), r.key(), source,
                    "RestTemplate." + call.getNameAsString());
        }).stream().distinct().toList();
    }

    private String requestEntityVerb(Expression e, Env env) {
        while (e instanceof MethodCallExpr mc && mc.getScope().isPresent()) {
            if (mc.getScope().get() instanceof NameExpr n && n.getNameAsString().equals("RequestEntity")) {
                String name = mc.getNameAsString();
                return name.equals("method") && !mc.getArguments().isEmpty() ? urls.verb(mc.getArgument(0), env)
                        : Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(name) ? name.toUpperCase() : "ANY";
            }
            e = mc.getScope().get();
        }
        return "ANY";
    }
}
