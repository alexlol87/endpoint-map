package com.endpointmap.endpoints;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.endpointmap.workspace.Workspace.SourceFile;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Endpoints from {@code @RestController}/{@code @Controller} classes: class-level {@code @RequestMapping}
 * prefixes, verb mappings, mappings inherited from superclasses and (super-)interfaces such as
 * {@code @HttpExchange} APIs, prefixed with the repo's servlet context path / WebFlux base path.
 * A mapping without a {@code method} gets the verb {@code ANY}: it serves every verb.
 */
public final class AnnotationEndpointDetector {

    private static final Map<String, String> FIXED_VERB = Map.ofEntries(
            Map.entry("GetMapping", "GET"), Map.entry("PostMapping", "POST"), Map.entry("PutMapping", "PUT"),
            Map.entry("PatchMapping", "PATCH"), Map.entry("DeleteMapping", "DELETE"),
            Map.entry("GetExchange", "GET"), Map.entry("PostExchange", "POST"), Map.entry("PutExchange", "PUT"),
            Map.entry("PatchExchange", "PATCH"), Map.entry("DeleteExchange", "DELETE"));
    private static final List<String> TYPE_MAPPINGS = List.of("RequestMapping", "HttpExchange");

    private final Workspace ws;
    private final Types types;
    private final ConstantEvaluator constants;
    private final List<String> warnings = new ArrayList<>();

    public AnnotationEndpointDetector(Workspace ws) {
        this.ws = ws;
        this.types = new Types(ws);
        this.constants = new ConstantEvaluator(types);
    }

    /** Problems seen by the last {@link #detect} run, such as config files that could not be read. */
    public List<String> warnings() {
        return warnings;
    }

    public List<Endpoint> detect(String profile) {
        List<Endpoint> out = new ArrayList<>();
        for (Repo repo : ws.repos()) {
            Map<String, String> config = ConfigResolver.load(repo.dir(), profile, warnings);
            String contextPath = ConfigResolver.resolve(config.getOrDefault("server.servlet.context-path",
                    config.getOrDefault("spring.webflux.base-path", "")), config);
            for (SourceFile sf : repo.sources()) {
                for (ClassOrInterfaceDeclaration c : sf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                    if (!c.isInterface() && (Workspace.hasAnnotation(c, "RestController") || Workspace.hasAnnotation(c, "Controller"))) {
                        detect(repo, sf, c, contextPath, out);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Walks the controller, then its superclasses and interfaces breadth-first. A method is mapped by the first
     * mapping found for its name/arity, and handled by the most derived implementation.
     */
    private void detect(Repo repo, SourceFile sf, ClassOrInterfaceDeclaration controller, String contextPath, List<Endpoint> out) {
        List<ClassOrInterfaceDeclaration> hierarchy = hierarchy(controller);
        List<String> classPrefixes = hierarchy.stream().filter(t -> !t.isInterface() && hasTypeMapping(t))
                .findFirst().map(this::typePrefixes).orElse(List.of(""));
        Map<String, MethodDeclaration> impls = new HashMap<>();
        Set<String> mapped = new HashSet<>();
        for (ClassOrInterfaceDeclaration type : hierarchy) {
            List<String> prefixes = type.isInterface() && hasTypeMapping(type) ? typePrefixes(type) : classPrefixes;
            for (MethodDeclaration m : type.getMethods()) {
                String sig = m.getNameAsString() + "/" + m.getParameters().size();
                MethodDeclaration handler = impls.computeIfAbsent(sig, k -> m);
                if (mapping(m).isPresent() && mapped.add(sig)) {
                    String file = handler.findCompilationUnit().orElse(null) == sf.cu() ? sf.relPath() : types.relPath(handler);
                    emit(repo, prefixes, controller, m, handler, file, contextPath, out);
                }
            }
        }
    }

    private List<ClassOrInterfaceDeclaration> hierarchy(ClassOrInterfaceDeclaration controller) {
        List<ClassOrInterfaceDeclaration> out = new ArrayList<>();
        Set<ClassOrInterfaceDeclaration> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<ClassOrInterfaceDeclaration> queue = new ArrayDeque<>(List.of(controller));
        while (!queue.isEmpty()) {
            ClassOrInterfaceDeclaration t = queue.poll();
            if (!seen.add(t)) {
                continue;
            }
            out.add(t);
            List<ClassOrInterfaceType> supers = new ArrayList<>(t.getExtendedTypes());
            supers.addAll(t.getImplementedTypes());
            for (ClassOrInterfaceType st : supers) {
                types.resolve(st).filter(ClassOrInterfaceDeclaration.class::isInstance)
                        .map(ClassOrInterfaceDeclaration.class::cast).ifPresent(queue::add);
            }
        }
        return out;
    }

    private static boolean hasTypeMapping(ClassOrInterfaceDeclaration t) {
        return t.getAnnotations().stream().anyMatch(a -> TYPE_MAPPINGS.contains(a.getName().getIdentifier()));
    }

    private void emit(Repo repo, List<String> prefixes, ClassOrInterfaceDeclaration controller,
                      MethodDeclaration declared, MethodDeclaration handler, String file, String contextPath,
                      List<Endpoint> out) {
        Optional<AnnotationExpr> mapping = mapping(declared);
        if (mapping.isEmpty()) {
            return;
        }
        AnnotationExpr a = mapping.get();
        List<String> verbs = FIXED_VERB.containsKey(a.getName().getIdentifier())
                ? List.of(FIXED_VERB.get(a.getName().getIdentifier())) : verbs(a);
        List<String> paths = paths(a, declared);
        int line = handler.getBegin().map(p -> p.line).orElse(0);
        for (String prefix : prefixes) {
            for (String path : paths) {
                String full = UrlPaths.join(contextPath, prefix, path);
                for (String verb : verbs) {
                    out.add(new Endpoint(repo.name(), verb, full,
                            controller.getNameAsString() + "." + handler.getNameAsString(), file, line, "annotation"));
                }
            }
        }
    }

    private static Optional<AnnotationExpr> mapping(MethodDeclaration m) {
        return m.getAnnotations().stream()
                .filter(a -> FIXED_VERB.containsKey(a.getName().getIdentifier()) || TYPE_MAPPINGS.contains(a.getName().getIdentifier()))
                .findFirst();
    }

    private List<String> typePrefixes(ClassOrInterfaceDeclaration type) {
        return type.getAnnotations().stream()
                .filter(a -> TYPE_MAPPINGS.contains(a.getName().getIdentifier()))
                .findFirst()
                .map(a -> paths(a, type))
                .orElse(List.of(""));
    }

    /** Values of value/path/url; no attribute means the empty path. Unknown expressions become {*}. */
    private List<String> paths(AnnotationExpr a, com.github.javaparser.ast.Node context) {
        List<Expression> values = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr s) {
            values.add(s.getMemberValue());
        } else if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (List.of("value", "path", "url").contains(p.getNameAsString())) {
                    values.add(p.getValue());
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (Expression v : values) {
            for (Expression e : v instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(v)) {
                out.add(constants.eval(e, context).orElse("{*}"));
            }
        }
        return out.isEmpty() ? List.of("") : out;
    }

    /** method = RequestMethod.X / {..} / "X"; none means any verb. */
    private static List<String> verbs(AnnotationExpr a) {
        List<String> out = new ArrayList<>();
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (!p.getNameAsString().equals("method")) {
                    continue;
                }
                Expression v = p.getValue();
                for (Expression e : v instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(v)) {
                    if (e instanceof FieldAccessExpr f) {
                        out.add(f.getNameAsString());
                    } else if (e instanceof NameExpr ne) {
                        out.add(ne.getNameAsString());
                    } else if (e instanceof StringLiteralExpr s) {
                        out.add(s.asString().toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return out.isEmpty() ? List.of("ANY") : out;
    }

}
