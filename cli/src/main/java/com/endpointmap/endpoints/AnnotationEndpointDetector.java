package com.endpointmap.endpoints;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.endpointmap.workspace.Workspace.SourceFile;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints from {@code @RestController}/{@code @Controller} classes: class-level {@code @RequestMapping}
 * prefixes, verb mappings, and {@code @HttpExchange} interfaces the controller implements, prefixed with the
 * repo's servlet context path / WebFlux base path.
 */
public final class AnnotationEndpointDetector {

    private static final Map<String, String> FIXED_VERB = Map.ofEntries(
            Map.entry("GetMapping", "GET"), Map.entry("PostMapping", "POST"), Map.entry("PutMapping", "PUT"),
            Map.entry("PatchMapping", "PATCH"), Map.entry("DeleteMapping", "DELETE"),
            Map.entry("GetExchange", "GET"), Map.entry("PostExchange", "POST"), Map.entry("PutExchange", "PUT"),
            Map.entry("PatchExchange", "PATCH"), Map.entry("DeleteExchange", "DELETE"));
    private static final List<String> TYPE_MAPPINGS = List.of("RequestMapping", "HttpExchange");

    private final Workspace ws;
    private final ConstantEvaluator constants;

    public AnnotationEndpointDetector(Workspace ws) {
        this.ws = ws;
        this.constants = new ConstantEvaluator(ws);
    }

    public List<Endpoint> detect(String profile) {
        List<Endpoint> out = new ArrayList<>();
        for (Repo repo : ws.repos()) {
            Map<String, String> config = ConfigResolver.load(repo.dir(), profile);
            String contextPath = config.getOrDefault("server.servlet.context-path",
                    config.getOrDefault("spring.webflux.base-path", ""));
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

    private void detect(Repo repo, SourceFile sf, ClassOrInterfaceDeclaration controller, String contextPath, List<Endpoint> out) {
        List<String> controllerPrefixes = typePrefixes(controller);
        for (MethodDeclaration m : controller.getMethods()) {
            emit(repo, controllerPrefixes, controller, m, m, sf.relPath(), contextPath, out);
        }
        // shortcut: only directly implemented interfaces are followed, not superclasses or inherited interfaces
        for (ClassOrInterfaceType it : controller.getImplementedTypes()) {
            Optional<TypeDeclaration<?>> type = constants.type(it.getNameAsString());
            if (type.isEmpty() || !(type.get() instanceof ClassOrInterfaceDeclaration api) || !api.isInterface()) {
                continue;
            }
            List<String> prefixes = api.getAnnotations().stream().anyMatch(a -> TYPE_MAPPINGS.contains(a.getName().getIdentifier()))
                    ? typePrefixes(api) : controllerPrefixes;
            for (MethodDeclaration declared : api.getMethods()) {
                MethodDeclaration impl = controller.getMethodsByName(declared.getNameAsString()).stream()
                        .filter(x -> x.getParameters().size() == declared.getParameters().size())
                        .findFirst().orElse(null);
                if (impl != null && mapping(impl).isPresent()) {
                    continue; // the controller re-declares the mapping, already emitted above
                }
                String file = impl != null ? sf.relPath() : fileOf(api);
                emit(repo, prefixes, controller, declared, impl != null ? impl : declared, file, contextPath, out);
            }
        }
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

    private String fileOf(TypeDeclaration<?> type) {
        return ws.repos().stream().flatMap(r -> r.sources().stream())
                .filter(s -> type.findCompilationUnit().map(cu -> cu == s.cu()).orElse(false))
                .map(SourceFile::relPath).findFirst().orElse("");
    }
}
