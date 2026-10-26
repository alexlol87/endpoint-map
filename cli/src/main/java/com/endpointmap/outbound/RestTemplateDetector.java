package com.endpointmap.outbound;

import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.url.UrlEvaluator;
import com.endpointmap.url.UrlEvaluator.Env;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Map;

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
        // shortcut: exchange(RequestEntity, ..) (one argument carrying verb and URL) is not read; add it if it shows up
        if (!(site.call() instanceof MethodCallExpr call) || !VERBS.containsKey(call.getNameAsString())
                || call.getScope().isEmpty() || call.getArguments().isEmpty()
                || VERBS.get(call.getNameAsString()).isEmpty() && call.getArguments().size() < 2
                || !urls.clientType(call.getScope().get()).filter("RestTemplate"::equals).isPresent()) {
            return List.of();
        }
        Expression client = call.getScope().get();
        Env env = Env.of(site);
        String fixed = VERBS.get(call.getNameAsString());
        String verb = fixed.isEmpty() ? urls.verb(call.getArgument(1), env) : fixed;
        return urls.each(env, e -> {
            String base = urls.client(client, e).text();
            String path = urls.string(call.getArgument(0), e).text();
            UrlEvaluator.Resolved r = UrlEvaluator.toCall(base, path, site.config());
            String source = path.startsWith(UrlEvaluator.ANY) ? call.getArgument(0).toString() : "\"" + r.raw() + "\"";
            return new OutboundCall("resttemplate", verb, r.baseUrl(), r.path(), r.key(), source,
                    "RestTemplate." + call.getNameAsString());
        }).stream().distinct().toList();
    }
}
