package com.endpointmap.endpoints;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.workspace.Names;
import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.endpointmap.workspace.Workspace.SourceFile;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
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

    /** Where routes being read land: the prefix and verb of every enclosing nest/path (ANY when none says). */
    private record Scope(String prefix, String verb) {
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
        notUnderstood.clear();
        for (Repo r : ws.repos()) {
            repo = r;
            basePath = ConfigResolver.basePath(ConfigResolver.load(r.dir(), profile, new ArrayList<>()));
            for (MethodDeclaration m : beans(r)) {
                method(m, null, new Scope("", "ANY"));
            }
        }
        return out;
    }

    private static List<MethodDeclaration> beans(Repo r) {
        return r.sources().stream().flatMap(sf -> sf.cu().findAll(MethodDeclaration.class).stream())
                .filter(m -> Workspace.hasAnnotation(m, "Bean") && returnsRouter(m)).toList();
    }

    private static boolean returnsRouter(MethodDeclaration m) {
        return m.getType() instanceof ClassOrInterfaceType t && t.getNameAsString().equals("RouterFunction");
    }

    private static boolean isBuilder(Type t) {
        return t instanceof ClassOrInterfaceType ct && ct.getNameAsString().equals("Builder")
                && ct.getScope().map(sc -> sc.getNameAsString().equals("RouterFunctions")).orElse(true);
    }

    /**
     * A RouterFunction-returning method, or a helper that takes or returns {@code RouterFunctions.Builder} ({@code param}:
     * its Builder parameter, the builder being filled). Its body is local variables (read where used), calls on the
     * builder and one {@code return}; a loop or an if fails the whole method.
     */
    private void method(MethodDeclaration m, String param, Scope s) {
        if (!walking.add(m)) {
            fail(m, "recursive router method " + m.getNameAsString());
            return;
        }
        List<Statement> body = m.getBody().map(BlockStmt::getStatements).map(List::copyOf).orElse(List.of());
        if (body.stream().allMatch(RouterEndpointDetector::readable)) {
            statements(body, param, returnsRouter(m), s);
        } else {
            // shortcut: routes built in loops or ifs are not followed; list them so the user sees them
            fail(m, "router method " + m.getNameAsString() + " is not local variables and one `return <routes>;` (built in a loop or an if?)");
        }
        walking.remove(m);
    }

    private static boolean readable(Statement s) {
        return s instanceof ReturnStmt r && r.getExpression().isPresent() || s instanceof ExpressionStmt es
                && (es.getExpression() instanceof VariableDeclarationExpr || es.getExpression() instanceof MethodCallExpr);
    }

    /**
     * {@code router}: the returned value is a RouterFunction, else a Builder. Calls that touch the builder {@code param}
     * are read; other calls (a local builder's own calls, logging) are read where that local is used, or ignored.
     */
    private void statements(List<Statement> body, String param, boolean router, Scope s) {
        for (Statement st : body) {
            if (st instanceof ReturnStmt r) {
                if (router) {
                    router(r.getExpression().get(), s);
                } else {
                    builder(r.getExpression().get(), param, s);
                }
            } else if (param != null && ((ExpressionStmt) st).getExpression() instanceof MethodCallExpr c && uses(c, param)) {
                builder(c, param, s);
            }
        }
    }

    private static boolean uses(Expression e, String name) {
        return !e.findAll(NameExpr.class, n -> n.getNameAsString().equals(name)).isEmpty();
    }

    /** An expression of type RouterFunction. */
    private void router(Expression e, Scope s) {
        e = unwrap(e);
        if (e instanceof NameExpr n) {
            Optional<Expression> init = initializer(n);
            Optional<MethodDeclaration> bean = init.isPresent() ? Optional.empty() : injectedBean(n);
            if (init.isPresent()) {
                router(init.get(), s);
            } else if (bean.isPresent()) {
                method(bean.get(), null, s);
            } else {
                fail(e, "router expression not understood: " + e);
            }
            return;
        }
        if (!(e instanceof MethodCallExpr c)) {
            fail(e, "router expression not understood: " + e);
            return;
        }
        Optional<MethodDeclaration> own = called(c, RouterEndpointDetector::returnsRouter);
        if (own.isPresent()) {
            method(own.get(), null, s);
            return;
        }
        String name = c.getNameAsString();
        List<Expression> a = c.getArguments();
        boolean isStatic = c.getScope().isEmpty() || isClass(c.getScope().get(), "RouterFunctions");
        if (name.equals("build") && a.isEmpty() && c.getScope().isPresent()) {
            builder(c.getScope().get(), null, s);
        } else if (isStatic && name.equals("route") && a.size() == 2) {
            route(a.get(0), a.get(1), s);
        } else if (isStatic && name.equals("nest") && a.size() == 2) {
            nest(a.get(0), s).ifPresent(in -> router(a.get(1), in));
        } else if (!isStatic && name.equals("andRoute") && a.size() == 2) {
            router(c.getScope().get(), s);
            route(a.get(0), a.get(1), s);
        } else if (!isStatic && name.equals("andNest") && a.size() == 2) {
            router(c.getScope().get(), s);
            nest(a.get(0), s).ifPresent(in -> router(a.get(1), in));
        } else if (!isStatic && (name.equals("and") || name.equals("andOther")) && a.size() == 1) {
            router(c.getScope().get(), s);
            router(a.get(0), s);
        } else if (!isStatic && PASS_THROUGH.contains(name)) {
            router(c.getScope().get(), s);
        } else {
            fail(c, "router call not understood: " + name + "(..)");
        }
    }

    /** A RouterFunction @Bean parameter or injected field: the @Bean method of that name in the same service. */
    private Optional<MethodDeclaration> injectedBean(NameExpr n) {
        Optional<Node> decl = Names.declaration(n.getNameAsString(), n);
        Type type = decl.map(d -> d instanceof Parameter p ? p.getType() : ((VariableDeclarator) d).getType()).orElse(null);
        if (!(type instanceof ClassOrInterfaceType t) || !t.getNameAsString().equals("RouterFunction")) {
            return Optional.empty();
        }
        // shortcut: no @Qualifier, the bean is found by its method name only; upgrade when qualified routers show up
        return beans(repo).stream().filter(m -> m.getNameAsString().equals(n.getNameAsString())).findFirst();
    }

    /**
     * A builder call chain ending at {@code e}: rooted at {@code route()} / {@code RouterFunctions.route()}, at the
     * builder {@code param} of an enclosing lambda or helper, at a local builder variable, or at a helper call.
     */
    private void builder(Expression e, String param, Scope s) {
        e = unwrap(e);
        if (e instanceof NameExpr n && n.getNameAsString().equals(param)) {
            return;
        }
        if (e instanceof NameExpr n && local(n).isPresent()) { // RouterFunctions.Builder b = route(); b.GET(..); ...
            VariableDeclarator v = local(n).get();
            builder(v.getInitializer().get(), param, s);
            Optional<Statement> declared = v.findAncestor(Statement.class);
            List<Statement> block = declared.flatMap(d -> d.getParentNode()).filter(BlockStmt.class::isInstance)
                    .map(b -> List.copyOf(((BlockStmt) b).getStatements())).orElse(List.of());
            statements(block.subList(block.indexOf(declared.orElse(null)) + 1, block.size()).stream()
                    .filter(st -> !(st instanceof ReturnStmt)).toList(), n.getNameAsString(), false, s);
            return;
        }
        if (!(e instanceof MethodCallExpr c)) {
            fail(e, "route builder not understood: " + e);
            return;
        }
        String name = c.getNameAsString();
        List<Expression> a = c.getArguments();
        if (name.equals("route") && a.isEmpty() && (c.getScope().isEmpty() || isClass(c.getScope().get(), "RouterFunctions"))) {
            return; // the builder's start
        }
        Optional<MethodDeclaration> helper = called(c, m -> isBuilder(m.getType()) || m.getParameters().stream().anyMatch(p -> isBuilder(p.getType())));
        if (helper.isPresent()) { // common(b, h): its builder argument first, then its body fills the same builder
            List<Parameter> ps = helper.get().getParameters();
            String inner = null;
            for (int i = 0; i < ps.size(); i++) {
                if (isBuilder(ps.get(i).getType())) {
                    builder(a.get(i), param, s);
                    inner = inner == null ? ps.get(i).getNameAsString() : inner;
                }
            }
            method(helper.get(), inner, s);
            return;
        }
        if (c.getScope().isEmpty()) {
            fail(c, "route builder not understood: " + name + "(..)");
            return;
        }
        builder(c.getScope().get(), param, s);
        if (VERBS.contains(name) && !a.isEmpty() && a.size() <= 3) {
            // GET(handler), GET(pattern, handler), GET(predicate, handler), GET(pattern, predicate, handler)
            String path = "";
            for (int i = 0; i < a.size() - 1; i++) {
                Expression x = a.get(i);
                boolean pattern = a.size() == 3 ? i == 0 : string(x).isPresent() || !looksLikePredicate(x);
                Optional<String> p = pattern ? path(x) : predicate(x).map(Predicate::path);
                if (p.isEmpty()) {
                    return;
                }
                path = UrlPaths.join(path, p.get());
            }
            emit(name, path, a.get(a.size() - 1), s);
        } else if (name.equals("route") && a.size() == 2) {
            route(a.get(0), a.get(1), s);
        } else if (name.equals("path") && a.size() == 2) {
            path(a.get(0)).ifPresent(p -> nested(a.get(1), new Scope(UrlPaths.join(s.prefix(), p), s.verb())));
        } else if (name.equals("nest") && a.size() == 2) {
            nest(a.get(0), s).ifPresent(in -> nested(a.get(1), in));
        } else if (name.equals("add") && a.size() == 1) {
            router(a.get(0), s);
        } else if (!PASS_THROUGH.contains(name)) {
            fail(c, "route builder call not understood: " + name + "(..)");
        }
    }

    /**
     * The second argument of builder {@code path}/{@code nest}: {@code b -> b.GET(..)...}, {@code () -> routes}, or a
     * method reference to a {@code void x(Builder b)} / {@code RouterFunction x()} method.
     */
    private void nested(Expression arg, Scope s) {
        arg = unwrap(arg);
        if (arg instanceof MethodReferenceExpr ref) {
            Optional<MethodDeclaration> m = scopeType(ref.getScope()).stream().flatMap(t -> methods(t, ref.getIdentifier()))
                    .filter(md -> md.getBody().isPresent() && (md.getParameters().size() == 1 && isBuilder(md.getParameter(0).getType())
                            || md.getParameters().isEmpty() && returnsRouter(md))).findFirst();
            if (m.isPresent()) {
                method(m.get(), m.get().getParameters().isEmpty() ? null : m.get().getParameter(0).getNameAsString(), s);
                return;
            }
        }
        if (!(arg instanceof LambdaExpr l) || l.getParameters().size() > 1) {
            fail(arg, "nested routes not understood: " + arg);
            return;
        }
        String param = l.getParameters().isEmpty() ? null : l.getParameter(0).getNameAsString(); // Consumer<Builder>, else Supplier<RouterFunction>
        if (l.getBody() instanceof ExpressionStmt es) {
            if (param == null) {
                router(es.getExpression(), s);
            } else {
                builder(es.getExpression(), param, s);
            }
            return;
        }
        List<Statement> body = ((BlockStmt) l.getBody()).getStatements();
        body.stream().filter(st -> !readable(st)).forEach(st -> fail(st, "nested routes statement not understood: " + st));
        statements(body.stream().filter(RouterEndpointDetector::readable).toList(), param, param == null, s);
    }

    private void route(Expression predicate, Expression handler, Scope s) {
        predicate(predicate).ifPresent(p -> emit(p.verb(), p.path(), handler, s));
    }

    /** The scope inside {@code nest(predicate, ..)}: its path joins the prefix, its verb must agree with the outer one. */
    private Optional<Scope> nest(Expression predicate, Scope s) {
        Optional<Predicate> p = predicate(predicate);
        if (p.isEmpty()) {
            return Optional.empty();
        }
        String verb = merge(s.verb(), p.get().verb());
        return verb == null ? fail(predicate, "predicate matches no request: " + predicate + " inside a " + s.verb() + "-only nest")
                : Optional.of(new Scope(UrlPaths.join(s.prefix(), p.get().path()), verb));
    }

    /** The verb both match: ANY gives way to the other; null when they differ. */
    private static String merge(String a, String b) {
        return a.equals("ANY") ? b : b.equals("ANY") || a.equals(b) ? a : null;
    }

    /** {@code GET("/x")}, {@code path("/x")}, {@code method(HttpMethod.GET)}, {@code a.and(b)}; accept(..) etc. ignored. */
    private Optional<Predicate> predicate(Expression e) {
        e = unwrap(e);
        if (e instanceof NameExpr n && initializer(n).isPresent()) { // RequestPredicate json = accept(..);
            return predicate(initializer(n).get());
        }
        if (e instanceof MethodCallExpr c) {
            String name = c.getNameAsString();
            List<Expression> a = c.getArguments();
            boolean isStatic = c.getScope().isEmpty() || isClass(c.getScope().get(), "RequestPredicates");
            if (!isStatic && name.equals("and") && a.size() == 1) {
                Optional<Predicate> l = predicate(c.getScope().get()), r = predicate(a.get(0));
                if (l.isEmpty() || r.isEmpty()) {
                    return Optional.empty();
                }
                String verb = merge(l.get().verb(), r.get().verb());
                return verb == null ? fail(c, "predicate matches no request: " + c)
                        : Optional.of(new Predicate(verb, UrlPaths.join(l.get().path(), r.get().path())));
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

    /** A builder verb's first argument that is not a path string: a predicate call or a RequestPredicate variable. */
    private boolean looksLikePredicate(Expression e) {
        e = unwrap(e);
        if (e instanceof NameExpr n && initializer(n).isPresent()) {
            return looksLikePredicate(initializer(n).get());
        }
        return e instanceof MethodCallExpr c && (VERBS.contains(c.getNameAsString()) || IGNORED_PREDICATES.contains(c.getNameAsString())
                || Set.of("path", "method", "and", "or", "negate").contains(c.getNameAsString()));
    }

    private Optional<String> path(Expression e) {
        Optional<String> s = string(e);
        return s.isPresent() ? s : fail(e, "route path not understood: " + e);
    }

    /** A constant string, also through locals: {@code String base = "/v1"; .. base + "/x"}. */
    private Optional<String> string(Expression e) {
        Optional<String> s = constants.eval(e, e);
        e = unwrap(e);
        if (s.isEmpty() && e instanceof NameExpr n && initializer(n).isPresent()) {
            return string(initializer(n).get());
        }
        if (s.isEmpty() && e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> l = string(b.getLeft()), r = string(b.getRight());
            return l.isPresent() && r.isPresent() ? Optional.of(l.get() + r.get()) : Optional.empty();
        }
        return s;
    }

    private static Optional<String> verb(Expression e) {
        String v = e instanceof FieldAccessExpr f ? f.getNameAsString() : e instanceof NameExpr n ? n.getNameAsString() : "";
        return VERBS.contains(v) ? Optional.of(v) : Optional.empty();
    }

    /** A local variable or field holding one value: its declarator, when it has an initializer and is never assigned again. */
    private static Optional<VariableDeclarator> local(NameExpr n) {
        return Names.declaration(n.getNameAsString(), n).filter(VariableDeclarator.class::isInstance).map(VariableDeclarator.class::cast)
                .filter(v -> v.getInitializer().isPresent()).filter(v -> {
                    Node owner = v.getParentNode().orElse(null) instanceof VariableDeclarationExpr
                            ? v.findAncestor(BlockStmt.class).map(Node.class::cast).orElse(v) : v.findAncestor(TypeDeclaration.class).map(Node.class::cast).orElse(v);
                    return owner.findAll(AssignExpr.class, x -> x.getTarget() instanceof NameExpr t && t.getNameAsString().equals(n.getNameAsString())
                            || x.getTarget() instanceof FieldAccessExpr f && f.getNameAsString().equals(n.getNameAsString())).isEmpty();
                });
    }

    private static Optional<Expression> initializer(NameExpr n) {
        return local(n).flatMap(VariableDeclarator::getInitializer);
    }

    /** One endpoint; its handler is the referenced method, the lambda itself, or nothing traceable (then reported). */
    private void emit(String verb, String path, Expression handler, Scope s) {
        String v = merge(s.verb(), verb);
        if (v == null) {
            fail(handler, "route " + verb + " " + path + " inside a " + s.verb() + "-only nest matches no request");
            return;
        }
        String full = UrlPaths.join(basePath, UrlPaths.join(s.prefix(), path));
        handler = unwrap(handler);
        if (handler instanceof MethodReferenceExpr ref) {
            Optional<MethodDeclaration> m = referenced(ref);
            if (m.isPresent()) {
                String owner = m.get().findAncestor(TypeDeclaration.class).map(TypeDeclaration::getNameAsString).orElse("");
                out.add(new Endpoint(repo.name(), v, full, owner + "." + m.get().getNameAsString(), types.relPath(m.get()),
                        line(m.get()), "router", m.get()));
                return;
            }
        } else if (handler instanceof LambdaExpr l) {
            // the same name FlowTracer gives the lambda; the endpoint's line tells two lambdas of one method apart
            String in = l.findAncestor(MethodDeclaration.class).map(md -> md.findAncestor(TypeDeclaration.class)
                    .map(t -> t.getNameAsString() + ".").orElse("") + md.getNameAsString()).orElse("");
            out.add(new Endpoint(repo.name(), v, full, in + "$lambda", types.relPath(l), line(l), "router", l));
            return;
        }
        fail(handler, "handler of " + v + " " + full + " not understood (endpoint kept, not traced): " + handler);
        out.add(new Endpoint(repo.name(), v, full, handler.toString(), types.relPath(handler), line(handler), "router", null));
    }

    /**
     * {@code handler::search}: a one-parameter method of that name on the scope's type or a superclass, the one taking
     * ServerRequest when there are several (the nearest class's wins); empty when that still leaves a choice.
     */
    private Optional<MethodDeclaration> referenced(MethodReferenceExpr ref) {
        List<MethodDeclaration> one = scopeType(ref.getScope()).stream().flatMap(t -> methods(t, ref.getIdentifier()))
                .filter(m -> m.getParameters().size() == 1).toList();
        Optional<MethodDeclaration> request = one.stream().filter(m -> m.getParameter(0).getType().asString().endsWith("ServerRequest")).findFirst();
        return request.isPresent() || one.size() != 1 ? request : Optional.of(one.get(0));
    }

    /** {@code adminRoutes(h)} / {@code this.x()} / {@code otherRoutes.x()}: a workspace method with a body that {@code kind} accepts. */
    private Optional<MethodDeclaration> called(MethodCallExpr c, java.util.function.Predicate<MethodDeclaration> kind) {
        Optional<TypeDeclaration<?>> owner = c.getScope().isEmpty() ? c.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t)
                : scopeType(c.getScope().get());
        return owner.stream().flatMap(t -> methods(t, c.getNameAsString()))
                .filter(m -> kind.test(m) && m.getParameters().size() == c.getArguments().size() && m.getBody().isPresent())
                .findFirst();
    }

    /** Methods named {@code name} on {@code t}, then on its workspace superclasses. */
    private java.util.stream.Stream<MethodDeclaration> methods(TypeDeclaration<?> t, String name) {
        List<MethodDeclaration> found = new ArrayList<>();
        Set<TypeDeclaration<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TypeDeclaration<?> at = t; at != null && seen.add(at); ) {
            found.addAll(at.getMethodsByName(name));
            at = at instanceof ClassOrInterfaceDeclaration c && !c.getExtendedTypes().isEmpty()
                    ? types.resolve(c.getExtendedTypes(0)).orElse(null) : null;
        }
        return found.stream();
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

    /** {@code RouterFunctions} / {@code RequestPredicates}, simple or fully qualified. */
    private static boolean isClass(Expression scope, String simpleName) {
        return scope instanceof NameExpr n && n.getNameAsString().equals(simpleName)
                || scope instanceof FieldAccessExpr f && f.getNameAsString().equals(simpleName);
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
