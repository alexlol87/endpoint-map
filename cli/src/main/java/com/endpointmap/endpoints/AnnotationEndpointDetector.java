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
import com.github.javaparser.ast.type.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
     * Walks the controller, then its superclasses and interfaces breadth-first. Every mapped method the controller
     * declares is emitted; an inherited mapping is skipped when a more derived type already mapped the same method,
     * and is handled by the most derived implementation. Methods match by name and erased parameter types, with the
     * type arguments each extends/implements clause binds (e.g. {@code CrudController<User, Long>}) substituted.
     */
    private void detect(Repo repo, SourceFile sf, ClassOrInterfaceDeclaration controller, String contextPath, List<Endpoint> out) {
        Map<ClassOrInterfaceDeclaration, Map<String, String>> bind = new IdentityHashMap<>();
        List<ClassOrInterfaceDeclaration> hierarchy = hierarchy(controller, bind);
        List<String> classPrefixes = hierarchy.stream().filter(t -> !t.isInterface() && hasTypeMapping(t))
                .findFirst().map(this::typePrefixes).orElse(List.of(""));
        List<MethodDeclaration> seen = new ArrayList<>();
        List<MethodDeclaration> mapped = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : hierarchy) {
            List<String> prefixes = type.isInterface() && hasTypeMapping(type) ? typePrefixes(type) : classPrefixes;
            for (MethodDeclaration m : type.getMethods()) {
                MethodDeclaration handler = seen.stream().filter(o -> sameMethod(o, m, bind)).findFirst().orElse(m);
                seen.add(m);
                if (mapping(m).isEmpty() || mapped.stream().anyMatch(o -> o.getParentNode().orElse(null) != m.getParentNode().orElse(null) && sameMethod(o, m, bind))) {
                    continue;
                }
                mapped.add(m);
                String file = handler.findCompilationUnit().orElse(null) == sf.cu() ? sf.relPath() : types.relPath(handler);
                emit(repo, prefixes, controller, m, handler, file, contextPath, out);
            }
        }
    }

    /** Same name and erased parameter types; an unbound type variable (raw base, generic method) matches any type. */
    private static boolean sameMethod(MethodDeclaration a, MethodDeclaration b, Map<ClassOrInterfaceDeclaration, Map<String, String>> bind) {
        if (!a.getNameAsString().equals(b.getNameAsString()) || a.getParameters().size() != b.getParameters().size()) {
            return false;
        }
        for (int i = 0; i < a.getParameters().size(); i++) {
            String x = erased(a, i, bind), y = erased(b, i, bind);
            if (x != null && y != null && !x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    /** Erased type of parameter i, with its declaring type's bound type arguments substituted; null if unbound. */
    private static String erased(MethodDeclaration m, int i, Map<ClassOrInterfaceDeclaration, Map<String, String>> bind) {
        com.github.javaparser.ast.body.Parameter p = m.getParameter(i);
        String name = simpleName(p.getType());
        if (m.getTypeParameters().stream().anyMatch(t -> t.getNameAsString().equals(name))) {
            return null;
        }
        ClassOrInterfaceDeclaration owner = m.getParentNode().filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast).orElse(null);
        return subst(p.getType(), p.isVarArgs() ? 1 : 0, owner, bind);
    }

    /** {@code type} erased, as seen from {@code owner}: its type variables replaced by their bindings; null if unbound. */
    private static String subst(Type type, int dims, ClassOrInterfaceDeclaration owner, Map<ClassOrInterfaceDeclaration, Map<String, String>> bind) {
        if (type.isWildcardType()) {
            return null;
        }
        String name = simpleName(type), suffix = "[]".repeat(type.getArrayLevel() + dims);
        if (owner != null && owner.getTypeParameters().stream().anyMatch(t -> t.getNameAsString().equals(name))) {
            String bound = bind.getOrDefault(owner, Map.of()).get(name);
            return bound == null ? null : bound + suffix;
        }
        return name + suffix;
    }

    private static String simpleName(Type type) {
        // shortcut: simple names only (a.Foo == b.Foo), fine for overload matching; resolve types if that ever collides
        return type.getElementType().asString().replaceAll("<.*>", "").replaceAll("^.*\\.", "");
    }

    /** The controller and its supertypes, breadth-first; fills {@code bind} with each supertype's type-variable bindings. */
    private List<ClassOrInterfaceDeclaration> hierarchy(ClassOrInterfaceDeclaration controller,
                                                        Map<ClassOrInterfaceDeclaration, Map<String, String>> bind) {
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
                Optional<ClassOrInterfaceDeclaration> s = types.resolve(st).filter(ClassOrInterfaceDeclaration.class::isInstance)
                        .map(ClassOrInterfaceDeclaration.class::cast);
                if (s.isEmpty()) {
                    continue;
                }
                List<Type> args = st.getTypeArguments().map(List::copyOf).orElse(List.of());
                if (!bind.containsKey(s.get()) && args.size() == s.get().getTypeParameters().size()) {
                    Map<String, String> b = new java.util.HashMap<>();
                    for (int i = 0; i < args.size(); i++) {
                        String v = subst(args.get(i), 0, t, bind);
                        if (v != null) {
                            b.put(s.get().getTypeParameter(i).getNameAsString(), v);
                        }
                    }
                    bind.put(s.get(), b);
                }
                queue.add(s.get());
            }
        }
        return out;
    }

    private static boolean hasTypeMapping(ClassOrInterfaceDeclaration t) {
        return t.getAnnotations().stream().anyMatch(a -> TYPE_MAPPINGS.contains(a.getName().getIdentifier()));
    }

    /** Verbs and paths of a method's own mapping annotation; empty when the method is not mapped. */
    public Optional<Mapping> mappingOf(MethodDeclaration m) {
        return mapping(m).map(a -> new Mapping(FIXED_VERB.containsKey(a.getName().getIdentifier())
                ? List.of(FIXED_VERB.get(a.getName().getIdentifier())) : verbs(a), paths(a, m)));
    }

    public record Mapping(List<String> verbs, List<String> paths) {
    }

    private void emit(Repo repo, List<String> prefixes, ClassOrInterfaceDeclaration controller,
                      MethodDeclaration declared, MethodDeclaration handler, String file, String contextPath,
                      List<Endpoint> out) {
        Optional<Mapping> mapping = mappingOf(declared);
        if (mapping.isEmpty()) {
            return;
        }
        List<String> verbs = mapping.get().verbs();
        List<String> paths = mapping.get().paths();
        int line = handler.getBegin().map(p -> p.line).orElse(0);
        for (String prefix : prefixes) {
            for (String path : paths) {
                String full = UrlPaths.join(contextPath, prefix, path);
                for (String verb : verbs) {
                    out.add(new Endpoint(repo.name(), verb, full,
                            controller.getNameAsString() + "." + handler.getNameAsString(), file, line, "annotation", handler));
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
