package com.endpointmap.outbound;

import com.endpointmap.endpoints.ConstantEvaluator;
import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.url.UrlEvaluator;
import com.endpointmap.url.UrlEvaluator.Env;
import com.endpointmap.workspace.Types;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis: {@code RedisTemplate}, {@code StringRedisTemplate} and their reactive forms, {@code opsForValue/Hash/List/
 * Set/ZSet().<op>(key, ..)} (also through an {@code XxxOperations} variable) and {@code delete/expire/hasKey}; the key
 * is evaluated like a URL and cut at its first unknown part ({@code "cart:" + id} -> {@code cart:*}; nothing known:
 * {@code *}, low confidence). A call to a workspace method with {@code @Cacheable/@CachePut/@CacheEvict} (also inside
 * {@code @Caching}, names defaulting to the class's {@code @CacheConfig}) touches {@code <cacheName>::*}, Spring's
 * default Redis cache key; such a call is still walked into.
 */
public final class RedisDetector implements OutboundDetector {

    private static final Set<String> TEMPLATES = Set.of("RedisTemplate", "StringRedisTemplate", "ReactiveRedisTemplate",
            "ReactiveStringRedisTemplate", "RedisOperations", "ReactiveRedisOperations");
    private static final Set<String> KEY_OPS = Set.of("delete", "unlink", "expire", "expireAt", "persist", "hasKey", "getExpire");
    private static final Map<String, String> STRUCTURES = Map.of("opsForValue", "value", "opsForHash", "hash",
            "opsForList", "list", "opsForSet", "set", "opsForZSet", "zset");
    private static final Map<String, String> CACHE_OPS = Map.of("Cacheable", "cache.get", "CachePut", "cache.put",
            "CacheEvict", "cache.evict");

    private final UrlEvaluator urls;
    private final ConstantEvaluator constants;

    public RedisDetector(UrlEvaluator urls, ConstantEvaluator constants) {
        this.urls = urls;
        this.constants = constants;
    }

    @Override
    public List<OutboundCall> detect(CallSite site) {
        if (!(site.call() instanceof MethodCallExpr mc)) {
            return List.of();
        }
        if (site.callees().size() == 1) {
            return cache(site.callees().get(0));
        }
        ClassOrInterfaceType t = site.receiverType();
        if (t == null || !site.receiverTypes().isEmpty() || !Types.fromPackage(t, "org.springframework.data.redis.")) {
            return List.of(); // a workspace type of the same name is not the framework's
        }
        String type = t.getNameAsString();
        String name = mc.getNameAsString();
        if (TEMPLATES.contains(type) && KEY_OPS.contains(name) && !mc.getArguments().isEmpty()) {
            return List.of(key(mc.getArgument(0), name, type + "." + name, site));
        }
        // redis.opsForHash().put(key, ..): detected at opsForHash, whose parent call is the op
        if (TEMPLATES.contains(type) && STRUCTURES.containsKey(name) && mc.getParentNode().orElse(null) instanceof MethodCallExpr op
                && op.getScope().orElse(null) == mc && !op.getArguments().isEmpty()) {
            return List.of(key(op.getArgument(0), STRUCTURES.get(name) + "." + op.getNameAsString(),
                    type + "." + name + "." + op.getNameAsString(), site));
        }
        String structure = type.replaceFirst("^Reactive", "").replaceFirst("Operations$", "");
        String ops = "opsFor" + structure.replace("Zset", "ZSet");
        if (type.endsWith("Operations") && STRUCTURES.containsKey(ops) && !mc.getArguments().isEmpty()) {
            return List.of(key(mc.getArgument(0), STRUCTURES.get(ops) + "." + name, type + "." + name, site));
        }
        return List.of();
    }

    private OutboundCall key(Expression e, String op, String label, CallSite site) {
        List<String> patterns = urls.each(Env.of(site), env -> pattern(urls.string(e, env).text())).stream().distinct().toList();
        // shortcut: several possible keys keep only the first; list them all if a real service builds keys by branches
        String p = patterns.isEmpty() ? "*" : patterns.get(0);
        return OutboundCall.redis(p, null, op, label, p.equals("*"));
    }

    /** The key's literal prefix up to the first part not known from code, then {@code *}. */
    static String pattern(String key) {
        int cut = key.length();
        for (String unknown : List.of(UrlEvaluator.ANY, "${")) {
            int i = key.indexOf(unknown);
            cut = i < 0 ? cut : Math.min(cut, i);
        }
        return cut == key.length() ? key : key.substring(0, cut) + "*";
    }

    // shortcut: the cache manager is assumed to be Redis and self-invocation (no proxy) still counts; check the
    // CacheManager bean and the receiver when a service mixes cache stores
    private List<OutboundCall> cache(MethodDeclaration m) {
        TypeDeclaration<?> owner = m.findAncestor(TypeDeclaration.class).orElse(null);
        String label = (owner == null ? "" : owner.getNameAsString() + ".") + m.getNameAsString();
        List<String> defaults = java.util.Optional.ofNullable(owner).flatMap(t -> t.getAnnotationByName("CacheConfig"))
                .map(a -> names(a)).orElse(List.of());
        List<OutboundCall> out = new ArrayList<>();
        for (AnnotationExpr top : m.getAnnotations()) {
            for (AnnotationExpr a : top.findAll(AnnotationExpr.class)) {
                String op = CACHE_OPS.get(a.getNameAsString());
                if (op == null) {
                    continue;
                }
                List<String> names = names(a);
                for (String n : names.isEmpty() ? defaults : names) {
                    out.add(OutboundCall.redis(n + "::*", n, op, "@" + a.getNameAsString() + " " + label, false));
                }
            }
        }
        return out.stream().distinct().toList();
    }

    /** {@code value}/{@code cacheNames} of a cache annotation: a string, constant or array of them. */
    private List<String> names(AnnotationExpr a) {
        List<Expression> values = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr s) {
            values.add(s.getMemberValue());
        } else if (a instanceof NormalAnnotationExpr n) {
            n.getPairs().stream().filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("cacheNames"))
                    .forEach(p -> values.add(p.getValue()));
        }
        List<String> out = new ArrayList<>();
        for (Expression v : values) {
            for (Expression e : v instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(v)) {
                out.add(constants.eval(e, a).orElse(e.toString()));
            }
        }
        return out;
    }
}
