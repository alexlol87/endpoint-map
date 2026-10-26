package com.endpointmap.outbound;

import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.url.UrlEvaluator;
import com.endpointmap.url.UrlEvaluator.Env;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * WebClient and RestClient request chains: {@code client.get()/.post()/.method(HttpMethod.X)...} followed by
 * {@code .uri(...)}. The base URL comes from the client expression (see {@link UrlEvaluator#client}); the
 * {@code uri} argument is a template string, a URL built in code, or a {@code UriBuilder} function. An absolute
 * {@code uri} replaces the base.
 */
public final class WebClientDetector implements OutboundDetector {

    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete", "head", "options", "method");

    private final UrlEvaluator urls;

    public WebClientDetector(UrlEvaluator urls) {
        this.urls = urls;
    }

    @Override
    public List<OutboundCall> detect(CallSite site) {
        if (!(site.call() instanceof MethodCallExpr uri) || !uri.getNameAsString().equals("uri") || uri.getArguments().isEmpty()
                || !(uri.getScope().orElse(null) instanceof MethodCallExpr verbCall)
                || !VERBS.contains(verbCall.getNameAsString()) || verbCall.getScope().isEmpty()) {
            return List.of();
        }
        Expression client = verbCall.getScope().get();
        Optional<String> type = urls.clientType(client);
        if (type.isEmpty()) {
            return List.of();
        }
        Env env = Env.of(site);
        String verb = verbCall.getNameAsString().equals("method")
                ? verbCall.getArguments().isEmpty() ? "ANY" : urls.verb(verbCall.getArgument(0), env)
                : verbCall.getNameAsString().toUpperCase(Locale.ROOT);
        // a ternary or helper with several returns: one call per possible URL
        return urls.each(env, e -> {
            String base = urls.client(client, e).text();
            String path = urls.string(uriExpression(uri.getArgument(0)), e).text();
            UrlEvaluator.Resolved r = UrlEvaluator.toCall(base, path, site.config());
            // the URL template as a hint; the code itself when the whole URI is built at runtime
            String source = path.startsWith(UrlEvaluator.ANY) ? uri.getArgument(0).toString() : "\"" + r.raw() + "\"";
            return new OutboundCall(type.get().toLowerCase(Locale.ROOT), verb, r.baseUrl(), r.path(), r.key(),
                    source, type.get() + "." + verbCall.getNameAsString());
        }).stream().distinct().toList();
    }

    /** {@code uri(b -> b.path("/x").build())}: the builder chain; a block lambda: its return. */
    private static Expression uriExpression(Expression arg) {
        if (arg instanceof LambdaExpr l) {
            return l.getExpressionBody().or(() -> l.getBody().findFirst(ReturnStmt.class).flatMap(ReturnStmt::getExpression))
                    .orElse(arg);
        }
        return arg;
    }
}
