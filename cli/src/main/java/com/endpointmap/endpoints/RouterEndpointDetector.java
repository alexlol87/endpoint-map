package com.endpointmap.endpoints;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.workspace.Names;
import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.endpointmap.workspace.Workspace.SourceFile;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Endpoints from functional WebFlux routes (PLAN 5.3): every {@code @Bean} method returning {@code RouterFunction} is
 * walked, builder style ({@code route().GET(..).path(..).nest(..).add(..).build()}) and static style
 * ({@code route(GET(..), h::m).andRoute(..).and(..).andNest(..)}, {@code nest(path(..), ..)}), through calls to other
 * RouterFunction-returning methods. Prefixes join through every level and the repo's base path. Whatever the walker
 * can't read is listed in {@link #notUnderstood()}, never dropped silently.
 */
public final class RouterEndpointDetector {

    private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    /** Predicates that never change the verb or path: ignored. */
    private static final Set<String> IGNORED_PREDICATES = Set.of("accept", "contentType", "headers", "queryParam", "all");
    /** Calls on a router or builder that add no route. */
    private static final Set<String> PASS_THROUGH = Set.of("filter", "before", "after", "onError", "withAttribute", "withAttributes");

    /** What a predicate matches: verb (ANY when it doesn't say) and path. */
    private record Predicate(String verb, String path) {
    }

    private final Workspace ws;
    private final Types types;
    private final ConstantEvaluator constants;
    private final List<String> notUnderstood = new ArrayList<>();
    private final Set<MethodDeclaration> walking = Collections.newSetFromMap(new IdentityHashMap<>());
    private Repo repo;
    private String basePath;
    private List<Endpoint> out;

    public RouterEndpointDetector(Workspace ws, Types types, ConstantEvaluator constants) {
        this.ws = ws;
        this.types = types;
        this.constants = constants;
    }

    /** "file:line: what" for every route part the last {@link #detect} could not read, in workspace order. */
    public List<String> notUnderstood() {
        return notUnderstood;
    }

    public List<Endpoint> detect(String profile) {
        out = new ArrayList<>();
        for (Repo r : ws.repos()) {
            repo = r;
            basePath = ConfigResolver.basePath(ConfigResolver.load(r.dir(), profile, new ArrayList<>()));
            for (SourceFile sf : r.sources()) {
                for (MethodDeclaration m : sf.cu().findAll(MethodDeclaration.class)) {
                    if (Workspace.hasAnnotation(m, "Bean") && returnsRouter(m)) {
                        routerMethod(m, "");
                    }
                }
            }
        }
        return out;
    }

    private static boolean returnsRouter(MethodDeclaration m) {
        return m.getType() instanceof ClassOrInterfaceType t && t.getNameAsString().equals("RouterFunction");
    }

    /** A RouterFunction-returning method: its body must be one {@code return <routes>;}. */
    private void routerMethod(MethodDeclaration m, String prefix) {
        if (!walking.add(m)) {
            fail(m, "recursive router method " + m.getNameAsString());
            return;
        }
        List<Statement> body = m.getBody().map(BlockStmt::getStatements).map(List::copyOf).orElse(List.of());
        if (body.size() == 1 && body.get(0) instanceof ReturnStmt r && r.getExpression().isPresent()) {
            router(r.getExpression().get(), prefix);
        } else {
            // shortcut: routes built with local variables, loops or ifs are not followed; list them so the user sees them
            fail(m, "router method " + m.getNameAsString() + " is not a single `return <routes>;` (built in a loop or with variables?)");
        }
        walking.remove(m);
    }

    /** An expression of type RouterFunction. */
    private void router(Expression e, String prefix) {
        e = unwrap(e);
        if (!(e instanceof MethodCallExpr c)) {
            fail(e, "router expression not understood: " + e);
            return;
        }
        Optional<MethodDeclaration> own = routerMethodCalled(c);
        if (own.isPresent()) {
            routerMethod(own.get(), prefix);
            return;
        }
        String name = c.getNameAsString();
        List<Expression> a = c.getArguments();
        boolean isStatic = c.getScope().isEmpty() || isRouterFunctions(c.getScope().get());
        if (name.equals("build") && a.isEmpty() && c.getScope().isPresent()) {
            builder(c.getScope().get(), null, prefix);
        } else if (isStatic && name.equals("route") && a.size() == 2) {
            route(a.get(0), a.get(1), prefix);
        } else if (isStatic && name.equals("nest") && a.size() == 2) {
            predicate(a.get(0)).ifPresent(p -> router(a.get(1), UrlPaths.join(prefix, p.path())));
        } else if (!isStatic && name.equals("andRoute") && a.size() == 2) {
            router(c.getScope().get(), prefix);
            route(a.get(0), a.get(1), prefix);
        } else if (!isStatic && name.equals("andNest") && a.size() == 2) {
            router(c.getScope().get(), prefix);
            predicate(a.get(0)).ifPresent(p -> router(a.get(1), UrlPaths.join(prefix, p.path())));
        } else if (!isStatic && (name.equals("and") || name.equals("andOther")) && a.size() == 1) {
            router(c.getScope().get(), prefix);
            router(a.get(0), prefix);
        } else if (!isStatic && PASS_THROUGH.contains(name)) {
            router(c.getScope().get(), prefix);
        } else {
            fail(c, "router call not understood: " + name + "(..)");
        }
    }

    /**
     * A builder call chain ending at {@code e}: rooted at {@code route()} / {@code RouterFunctions.route()}, or at the
     * lambda parameter {@code param} of an enclosing {@code .path(..)} / {@code .nest(..)}.
     */
    private void builder(Expression e, String param, String prefix) {
        e = unwrap(e);
        if (param != null && e instanceof NameExpr n && n.getNameAsString().equals(param)) {
            return;
        }
        if (!(e instanceof MethodCallExpr c)) {
            fail(e, "route builder not understood: " + e);
            return;
        }
        String name = c.getNameAsString();
        List<Expression> a = c.getArguments();
        if (name.equals("route") && a.isEmpty() && (c.getScope().isEmpty() || isRouterFunctions(c.getScope().get()))) {
            return; // the builder's start
        }
        if (c.getScope().isEmpty()) {
            fail(c, "route builder not understood: " + name + "(..)");
            return;
        }
        builder(c.getScope().get(), param, prefix);
        if (VERBS.contains(name) && !a.isEmpty() && a.size() <= 3) {
            // GET(handler), GET(pattern, handler), GET(predicate, handler), GET(pattern, predicate, handler)
            String path = "";
            for (Expression x : a.subList(0, a.size() - 1)) {
                Optional<String> s = constants.eval(x, x);
                Optional<Predicate> p = s.isPresent() ? Optional.of(new Predicate("ANY", s.get())) : predicate(x);
                if (p.isEmpty()) {
                    return;
                }
                path = UrlPaths.join(path, p.get().path());
            }
            emit(name, UrlPaths.join(prefix, path), a.get(a.size() - 1));
        } else if (name.equals("route") && a.size() == 2) {
            route(a.get(0), a.get(1), prefix);
        } else if ((name.equals("path") || name.equals("nest")) && a.size() == 2) {
            Optional<String> s = name.equals("path") ? path(a.get(0)) : predicate(a.get(0)).map(Predicate::path);
            if (s.isPresent()) {
                nested(a.get(1), UrlPaths.join(prefix, s.get()));
            }
        } else if (name.equals("add") && a.size() == 1) {
            router(a.get(0), prefix);
        } else if (!PASS_THROUGH.contains(name)) {
            fail(c, "route builder call not understood: " + name + "(..)");
        }
    }

    /** The second argument of builder {@code path}/{@code nest}: {@code b -> b.GET(..)...} or {@code () -> routes}. */
    private void nested(Expression arg, String prefix) {
        if (!(unwrap(arg) instanceof LambdaExpr l) || l.getParameters().size() > 1) {
            fail(arg, "nested routes not understood: " + arg);
            return;
        }
        List<Expression> exprs = new ArrayList<>();
        if (l.getBody() instanceof ExpressionStmt es) {
            exprs.add(es.getExpression());
        } else {
            for (Statement s : ((BlockStmt) l.getBody()).getStatements()) {
                if (s instanceof ExpressionStmt es) {
                    exprs.add(es.getExpression());
                } else if (s instanceof ReturnStmt r && r.getExpression().isPresent() && l.getParameters().isEmpty()) {
                    exprs.add(r.getExpression().get());
                } else {
                    fail(s, "nested routes statement not understood: " + s);
                }
            }
        }
        for (Expression x : exprs) {
            if (l.getParameters().isEmpty()) {
                router(x, prefix); // Supplier<RouterFunction>
            } else {
                builder(x, l.getParameter(0).getNameAsString(), prefix); // Consumer<Builder>
            }
        }
    }

    private void route(Expression predicate, Expression handler, String prefix) {
        predicate(predicate).ifPresent(p -> emit(p.verb(), UrlPaths.join(prefix, p.path()), handler));
    }

    /** {@code GET("/x")}, {@code path("/x")}, {@code method(HttpMethod.GET)}, {@code a.and(b)}; accept(..) etc. ignored. */
    private Optional<Predicate> predicate(Expression e) {
        e = unwrap(e);
        if (e instanceof MethodCallExpr c) {
            String name = c.getNameAsString();
            List<Expression> a = c.getArguments();
            boolean isStatic = c.getScope().isEmpty() || c.getScope().get() instanceof NameExpr n && n.getNameAsString().equals("RequestPredicates");
            if (!isStatic && name.equals("and") && a.size() == 1) {
                Optional<Predicate> l = predicate(c.getScope().get()), r = predicate(a.get(0));
                if (l.isEmpty() || r.isEmpty()) {
                    return Optional.empty();
                }
                String lv = l.get().verb(), rv = r.get().verb();
                if (!lv.equals("ANY") && !rv.equals("ANY") && !lv.equals(rv)) {
                    return fail(c, "predicate matches no request: " + c);
                }
                return Optional.of(new Predicate(lv.equals("ANY") ? rv : lv, UrlPaths.join(l.get().path(), r.get().path())));
            }
            if (isStatic && VERBS.contains(name) && a.size() == 1) {
                return path(a.get(0)).map(p -> new Predicate(name, p));
            }
            if (isStatic && name.equals("path") && a.size() == 1) {
                return path(a.get(0)).map(p -> new Predicate("ANY", p));
            }
            if (isStatic && name.equals("method") && a.size() == 1 && verb(a.get(0)).isPresent()) {
                return Optional.of(new Predicate(verb(a.get(0)).get(), ""));
            }
            if (isStatic && IGNORED_PREDICATES.contains(name)) {
                return Optional.of(new Predicate("ANY", ""));
            }
        }
        return fail(e, "route predicate not understood: " + e);
    }

    private Optional<String> path(Expression e) {
        Optional<String> s = constants.eval(e, e);
        return s.isPresent() ? s : fail(e, "route path not understood: " + e);
    }

    private static Optional<String> verb(Expression e) {
        String v = e instanceof FieldAccessExpr f ? f.getNameAsString() : e instanceof NameExpr n ? n.getNameAsString() : "";
        return VERBS.contains(v) ? Optional.of(v) : Optional.empty();
    }

    /** One endpoint; its handler is the referenced method, the lambda itself, or nothing traceable (then reported). */
    private void emit(String verb, String path, Expression handler) {
        String full = UrlPaths.join(basePath, path);
        handler = unwrap(handler);
        if (handler instanceof MethodReferenceExpr ref) {
            Optional<MethodDeclaration> m = referenced(ref);
            if (m.isPresent()) {
                String owner = m.get().findAncestor(TypeDeclaration.class).map(TypeDeclaration::getNameAsString).orElse("");
                out.add(new Endpoint(repo.name(), verb, full, owner + "." + m.get().getNameAsString(), types.relPath(m.get()),
                        line(m.get()), "router", m.get()));
                return;
            }
        } else if (handler instanceof LambdaExpr l) {
            String in = l.findAncestor(MethodDeclaration.class).map(md -> md.findAncestor(TypeDeclaration.class)
                    .map(t -> t.getNameAsString() + ".").orElse("") + md.getNameAsString()).orElse("");
            out.add(new Endpoint(repo.name(), verb, full, in + "$lambda@L" + line(l), types.relPath(l), line(l), "router", l));
            return;
        }
        fail(handler, "handler of " + verb + " " + full + " not understood (endpoint kept, not traced): " + handler);
        out.add(new Endpoint(repo.name(), verb, full, handler.toString(), types.relPath(handler), line(handler), "router", null));
    }

    /** {@code handler::search}: the method named on the scope's declared type (one taking ServerRequest preferred). */
    private Optional<MethodDeclaration> referenced(MethodReferenceExpr ref) {
        List<MethodDeclaration> ms = scopeType(ref.getScope()).map(t -> t.getMethodsByName(ref.getIdentifier())).orElse(List.of());
        return ms.stream().filter(m -> m.getParameters().size() == 1).findFirst().or(() -> ms.stream().findFirst());
    }

    /** {@code adminRoutes(h)} / {@code this.x()} / {@code otherRoutes.x()}: a workspace method returning RouterFunction. */
    private Optional<MethodDeclaration> routerMethodCalled(MethodCallExpr c) {
        Optional<TypeDeclaration<?>> owner = c.getScope().isEmpty() ? c.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t)
                : scopeType(c.getScope().get());
        return owner.stream().flatMap(t -> t.getMethodsByName(c.getNameAsString()).stream())
                .filter(m -> returnsRouter(m) && m.getParameters().size() == c.getArguments().size() && m.getBody().isPresent())
                .findFirst();
    }

    /** Workspace type of a scope: {@code this}, a parameter/local/field's declared type, or a class name. */
    private Optional<TypeDeclaration<?>> scopeType(Expression scope) {
        if (scope instanceof ThisExpr) {
            return scope.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t);
        }
        String name = scope instanceof NameExpr n ? n.getNameAsString()
                : scope instanceof TypeExpr t ? t.getType().asString()
                : scope instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr ? f.getNameAsString() : null;
        if (name == null) {
            return Optional.empty();
        }
        Optional<Node> decl = Names.declaration(name, scope);
        if (decl.isPresent()) {
            Type type = decl.get() instanceof Parameter p ? p.getType() : ((VariableDeclarator) decl.get()).getType();
            return type instanceof ClassOrInterfaceType ct ? types.resolve(ct) : Optional.empty();
        }
        return types.byImports(name, scope).or(() -> types.bySimpleNameNear(name, scope));
    }

    private static boolean isRouterFunctions(Expression scope) {
        return scope instanceof NameExpr n && n.getNameAsString().equals("RouterFunctions");
    }

    private static Expression unwrap(Expression e) {
        return e instanceof EnclosedExpr en ? unwrap(en.getInner()) : e;
    }

    private <T> Optional<T> fail(Node at, String what) {
        notUnderstood.add(types.relPath(at) + ":" + line(at) + ": " + what.replaceAll("\\s+", " "));
        return Optional.empty();
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }
}
