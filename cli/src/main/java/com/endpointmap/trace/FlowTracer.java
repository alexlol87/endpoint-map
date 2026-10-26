package com.endpointmap.trace;

import com.endpointmap.workspace.Names;
import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
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
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks method bodies from a handler down through any number of calls (services, static utils, library code) and
 * asks every {@link OutboundDetector} to classify each call. A classified call ends that branch; any other call
 * into workspace code is followed. An interface/abstract method is followed into each implementation.
 * Calls the symbol solver can't resolve fall back to name + arity matching, low confidence when several match or
 * the receiver's type was a guess by simple name.
 * Each method keeps one result per call site and target, so repeated calls and diamonds don't multiply paths.
 */
public final class FlowTracer {

    /**
     * An outbound call reached from a method: {@code via} lists every method entered on the way, client method last;
     * {@code sites} the position (file:line:column) of each call on that path, the client call last. {@code wrapper}:
     * for a raw client call made in library code, the outermost library class the service code called
     * (PLAN: client "&lt;wrapper class&gt;"); null otherwise. {@code lossy}: it came through a recursive result reused for
     * other arguments, so its URL may belong to another path through the same call site.
     */
    public record Found(OutboundCall call, List<String> via, String file, int line, boolean lowConfidence, List<String> sites,
                        String wrapper, boolean lossy) {
    }

    private record Callees(List<MethodDeclaration> methods, boolean low) {
    }

    /** A method walked for a call; {@code on}: the implementing class it was found for, whose clauses bind type variables. */
    private record Impl(MethodDeclaration method, ClassOrInterfaceDeclaration on) {
    }

    /** A receiver's static type; {@code exact} when found by the solver, a scoped declaration or Java's import rules. */
    private record Typed(TypeDeclaration<?> type, boolean exact) {
    }

    /**
     * {@code backTo}: stack depth of the shallowest method a recursive call jumped back to, MAX_VALUE if none.
     * {@code lossy}: the recursive clusters (named by their first method) where a cut-short result was reused.
     */
    private record Result(List<Found> found, int backTo, Set<String> lossy) {
    }

    /** One edge per call site and target: further paths to it only change {@code via}. */
    private record Key(OutboundCall call, String file, int line) {
    }

    private final Types types;
    private final List<OutboundDetector> detectors;
    private final ArgumentBinder binder;
    private final Map<TypeDeclaration<?>, List<ClassOrInterfaceDeclaration>> subtypes = new IdentityHashMap<>();
    private final Map<Expression, Callees> callees = new IdentityHashMap<>();
    private final Map<Expression, List<TypeDeclaration<?>>> receivers = new IdentityHashMap<>();
    private final Map<Expression, Optional<ClassOrInterfaceType>> written = new IdentityHashMap<>();
    private final List<ClassOrInterfaceDeclaration> allInterfaces = new ArrayList<>();
    private Map<TypeDeclaration<?>, List<ClassOrInterfaceDeclaration>> extenders;
    // per service: detectors resolve placeholders with the calling service's config, so library results differ;
    // per argument values: a util called with another WebClient bean or URL reaches another target
    private final Map<Repo, Map<Node, Map<Map<String, ArgValue>, Result>>> memo = new IdentityHashMap<>();

    public FlowTracer(Workspace ws, Types types, List<OutboundDetector> detectors) {
        this(ws, types, detectors, ArgumentBinder.NONE);
    }

    public FlowTracer(Workspace ws, Types types, List<OutboundDetector> detectors, ArgumentBinder binder) {
        this.types = types;
        this.detectors = detectors;
        this.binder = binder;
        ws.repos().stream().flatMap(r -> r.sources().stream())
                .flatMap(sf -> sf.cu().findAll(ClassOrInterfaceDeclaration.class).stream())
                .forEach(c -> {
                    if (c.isInterface()) {
                        allInterfaces.add(c);
                    } else {
                        supertypes(c).forEach(s -> subtypes.computeIfAbsent(s, k -> new ArrayList<>()).add(c));
                    }
                });
    }

    /**
     * Distinct URLs one call path (the same chain of call sites) may give a handler; more is one callSites entry.
     * Argument combinations multiply along one path; separate calls through a shared helper each keep their edge.
     */
    public static final int MAX_URLS_PER_CALL_SITE = 4;

    /**
     * Cut-short walks inside one recursive cluster (counted at the cluster's first method) before its cut-short results
     * are reused whatever their arguments. Walks outside a cluster never count, so results don't depend on handler order.
     */
    static final int MAX_WALKS = 100;
    /** Cut-short walks per stack depth of the cluster's first method; reset when a method is entered at that depth. */
    private final List<Integer> clusterWalks = new ArrayList<>();
    private Set<String> lossy = Set.of();

    /**
     * {@code handler}: a handler method, or a route's handler lambda (its body is walked, see RouterEndpointDetector).
     * {@code controller}: the class the endpoint was found on (an inherited handler's subclass binds its type variables), or null.
     */
    public List<Found> trace(Node handler, TypeDeclaration<?> controller, Repo service, Map<String, String> config) {
        List<Impl> impls = handler instanceof MethodDeclaration md ? impls(md, service) : List.of();
        Result r;
        if (impls.isEmpty() || impls.get(0).method() == handler) {
            r = walk(handler, ownTypeArgs(controller, Map.of()), service, config, new IdentityHashMap<>(), new IdentityHashMap<>());
        } else { // a route handler typed as an interface: each implementation is walked, as for a call to it
            List<Found> all = new ArrayList<>();
            Set<String> lossyAll = new java.util.TreeSet<>();
            for (Impl impl : impls) {
                Result ri = walk(impl.method(), ownTypeArgs(impl.on(), Map.of()), service, config, new IdentityHashMap<>(), new IdentityHashMap<>());
                lossyAll.addAll(ri.lossy());
                ri.found().forEach(f -> all.add(new Found(f.call(), Stream.concat(Stream.of(label(impl.method())), f.via().stream()).toList(),
                        f.file(), f.line(), f.lowConfidence(), f.sites(), f.wrapper(), f.lossy())));
            }
            r = new Result(all, Integer.MAX_VALUE, Set.copyOf(lossyAll));
        }
        lossy = r.lossy();
        // a result reused across arguments may pair a URL with the wrong path: those URLs are capped per client call
        // site, not per path, so a mixed-up pairing never stands as a confident edge
        Map<List<String>, List<Found>> byPath = new LinkedHashMap<>();
        r.found().forEach(f -> byPath.computeIfAbsent(f.lossy() ? List.of("*", f.sites().get(f.sites().size() - 1)) : f.sites(),
                k -> new ArrayList<>()).add(f));
        Map<Key, Found> out = new LinkedHashMap<>(); // several capped paths to one site give the same {*} edge
        for (List<Found> site : byPath.values()) {
            // db calls (op set) have no URL to ask for: one edge per table, however many entities a fan-out gives
            if (site.size() <= MAX_URLS_PER_CALL_SITE || site.get(0).call().op() != null) {
                site.forEach(f -> add(out, f));
                continue;
            }
            // too many: one unknown URL (a callSites entry) instead, and none of the partly unknown {*} values
            Found first = site.stream().min(java.util.Comparator.comparingInt(f -> f.via().size())).orElseThrow();
            OutboundCall c = first.call();
            String verb = site.stream().map(f -> f.call().verb()).distinct().count() == 1 ? c.verb() : "ANY";
            String eg = site.stream().filter(f -> f.call().baseUrl() != null).findFirst().orElse(first).call().source();
            add(out, new Found(new OutboundCall(c.client(), verb, null, "{*}", null,
                    "one of " + site.size() + " combinations, e.g. " + eg, c.label()), first.via(), first.file(),
                    first.line(), first.lowConfidence(), first.sites(), first.wrapper(), first.lossy()));
        }
        return List.copyOf(out.values());
    }

    /** A handler with no body and no implementation in {@code service} or a library: nothing to trace. */
    public boolean bodiless(Node handler, Repo service) {
        return handler instanceof MethodDeclaration md && md.getBody().isEmpty() && implementations(md, service).get(0) == md;
    }

    /** Clusters of the last {@link #trace} explored only partly (some paths through them may be missing), sorted. */
    public Set<String> partialClusters() {
        return lossy;
    }

    /**
     * {@code stack} maps each method being walked to its depth. {@code partial} holds results cut short by a recursive
     * call back to a method still on the stack: valid until that method returns, so a cycle is walked once, not once
     * per path into it. {@code args}: what the caller passed to m's parameters.
     */
    private Result walk(Node m, Map<String, ArgValue> args, Repo service, Map<String, String> config,
                        Map<Node, Integer> stack, Map<Node, Map<Map<String, ArgValue>, Result>> partial) {
        Map<Map<String, ArgValue>, Result> done = memo.computeIfAbsent(service, k -> new IdentityHashMap<>())
                .computeIfAbsent(m, k -> new HashMap<>());
        if (done.containsKey(args)) {
            return done.get(args);
        }
        Map<Map<String, ArgValue>, Result> cuts = partial.getOrDefault(m, Map.of());
        if (cuts.containsKey(args)) {
            return cuts.get(args);
        }
        // a cut-short result holds for the same arguments only; past its cluster's budget any one is reused, else every
        // simple path through a mutually recursive cluster is walked (exponential). Its URLs are then marked lossy.
        Optional<Result> reuse = cuts.values().stream().filter(c -> clusterWalks.get(c.backTo()) > MAX_WALKS).findFirst();
        if (reuse.isPresent()) {
            Result c = reuse.get();
            String cluster = stack.entrySet().stream().filter(e -> e.getValue() == c.backTo()).map(e -> label(e.getKey()))
                    .findFirst().orElseThrow();
            Set<String> names = new java.util.TreeSet<>(c.lossy());
            names.add(cluster);
            return new Result(c.found().stream().map(f -> new Found(f.call(), f.via(), f.file(), f.line(), f.lowConfidence(),
                    f.sites(), f.wrapper(), true)).toList(), c.backTo(), Set.copyOf(names));
        }
        Optional<? extends Node> body = m instanceof LambdaExpr l ? Optional.of(l.getBody()) : ((MethodDeclaration) m).getBody();
        if (body.isEmpty()) {
            return new Result(List.of(), Integer.MAX_VALUE, Set.of());
        }
        // detectors see a lambda's enclosing method as the method being walked; a root lambda gets no arguments
        MethodDeclaration method = m instanceof MethodDeclaration md ? md : m.findAncestor(MethodDeclaration.class).orElse(null);
        int depth = stack.size();
        stack.put(m, depth);
        while (clusterWalks.size() <= depth) {
            clusterWalks.add(0);
        }
        clusterWalks.set(depth, 0);
        Map<Key, Found> found = new LinkedHashMap<>();
        Set<String> lossyHere = new java.util.TreeSet<>();
        int backTo = Integer.MAX_VALUE;
        // shortcut: the body is walked flat (no if/loop/try structure); the flow tree (#13) needs that structure
        for (Expression call : body.get().findAll(Expression.class,
                e -> e instanceof MethodCallExpr || e instanceof MethodReferenceExpr)) {
            // the same in every walk of m: a recursive cluster walks m once per argument combination
            Callees c = callees.computeIfAbsent(call, this::callees);
            List<TypeDeclaration<?>> receiver = receivers.computeIfAbsent(call, this::receiverTypes);
            ClassOrInterfaceType receiverType = written.computeIfAbsent(call, this::writtenType).orElse(null);
            boolean classified = false;
            // one detection per callee, so via names the right one; a framework call (WebClient...) has none
            for (MethodDeclaration callee : c.methods().isEmpty() ? Collections.<MethodDeclaration>singletonList(null) : c.methods()) {
                List<OutboundCall> out = detectors.stream().flatMap(d -> d.detect(new CallSite(call,
                        callee == null ? List.of() : List.of(callee), receiver, service, config, method, args, receiverType)).stream()).toList();
                for (OutboundCall o : out) {
                    // an abstract method is named as the caller sees it: ItemClient.get, not the inherited ItemApi.get
                    String label = o.label() != null ? o.label() : receiver.isEmpty() || callee.getBody().isPresent()
                            ? label(callee) : receiver.get(0).getNameAsString() + "." + callee.getNameAsString();
                    String wrapper = RAW_CLIENTS.contains(o.client()) && library(call)
                            ? enclosingType(call).map(TypeDeclaration::getNameAsString).orElse(null) : null;
                    add(found, new Found(o, List.of(label), types.relPath(call), line(call), c.low() || o.low(), List.of(site(call)), wrapper, false));
                }
                classified |= !out.isEmpty();
            }
            if (classified) {
                continue;
            }
            for (Impl impl : c.methods().stream().flatMap(d -> impls(d, service).stream()).distinct().toList()) {
                MethodDeclaration callee = impl.method();
                Integer at = stack.get(callee);
                if (at != null) {
                    backTo = Math.min(backTo, at); // recursion: skip only this back edge
                    continue;
                }
                List<Map<String, ArgValue>> bound = call instanceof MethodCallExpr mc
                        ? binder.bind(callee, mc, new CallSite(call, c.methods(), receiver, service, config, method, args, receiverType)) : List.of(Map.of());
                Map<String, ArgValue> typeArgs = new HashMap<>(typeArgs(call, receiverType, args));
                typeArgs.putAll(ownTypeArgs(impl.on(), args)); // the implementation that runs: OrderServiceImpl extends CrudSvc<Order>
                for (Map<String, ArgValue> calleeArgs : typeArgs.isEmpty() ? bound : bound.stream().map(b -> {
                    Map<String, ArgValue> both = new HashMap<>(b);
                    both.putAll(typeArgs);
                    return (Map<String, ArgValue>) both;
                }).toList()) {
                    Result r = walk(callee, calleeArgs, service, config, stack, partial);
                    backTo = Math.min(backTo, r.backTo());
                    lossyHere.addAll(r.lossy());
                    for (Found f : r.found()) {
                        List<String> via = new ArrayList<>(List.of(label(callee)));
                        via.addAll(f.via());
                        List<String> sites = new ArrayList<>(List.of(site(call)));
                        sites.addAll(f.sites());
                        // entering library code from the service's: that library class is the client the service calls
                        String wrapper = f.wrapper() != null && library(callee) && !library(m)
                                ? enclosingType(callee).map(TypeDeclaration::getNameAsString).orElse(f.wrapper()) : f.wrapper();
                        add(found, new Found(f.call(), List.copyOf(via), f.file(), f.line(), f.lowConfidence() || c.low(),
                                List.copyOf(sites), wrapper, f.lossy()));
                    }
                }
            }
        }
        stack.remove(m);
        partial.values().forEach(byArgs -> byArgs.values().removeIf(r -> r.backTo() >= depth)); // they were cut back to m or below, which is done now
        if (backTo >= depth) { // complete: any recursion below came back to m itself, not to a caller still being walked
            Result result = new Result(List.copyOf(found.values()), Integer.MAX_VALUE, Set.copyOf(lossyHere));
            done.put(args, result);
            return result;
        }
        clusterWalks.set(backTo, clusterWalks.get(backTo) + 1);
        Result result = new Result(List.copyOf(found.values()), backTo, Set.copyOf(lossyHere));
        partial.computeIfAbsent(m, k -> new LinkedHashMap<>()).put(args, result);
        return result;
    }

    /**
     * A certain path beats a low-confidence one, then the shorter {@code via} wins (a memoized result reached through
     * a cycle can carry a longer, looping chain), then the first found.
     */
    private static void add(Map<Key, Found> found, Found f) {
        found.merge(new Key(f.call(), f.file(), f.line()), f, (a, b) -> {
            Found w = a.lowConfidence() != b.lowConfidence() ? (a.lowConfidence() ? b : a) : b.via().size() < a.via().size() ? b : a;
            return a.lossy() || b.lossy() // lossy if either path was, so the capped grouping still sees it
                    ? new Found(w.call(), w.via(), w.file(), w.line(), w.lowConfidence(), w.sites(), w.wrapper(), true) : w;
        });
    }

    /** Static type of the call's receiver and its supertypes; empty when it has no scope or it can't be told. */
    private List<TypeDeclaration<?>> receiverTypes(Expression call) {
        Optional<Expression> scope = call instanceof MethodCallExpr mc ? mc.getScope()
                : Optional.of(((MethodReferenceExpr) call).getScope());
        return scope.flatMap(this::scopeType).map(Typed::type)
                .map(t -> Stream.concat(Stream.of(t), supertypes(t).stream()).toList()).orElse(List.of());
    }

    /** The receiver's declared type as written ({@code Base<Pet, Long>}), for a variable or field receiver; Spring's types too. */
    private Optional<ClassOrInterfaceType> writtenType(Expression call) {
        Optional<Expression> scope = call instanceof MethodCallExpr mc ? mc.getScope()
                : Optional.of(((MethodReferenceExpr) call).getScope());
        return scope.flatMap(s -> s instanceof NameExpr n ? declared(n.getNameAsString(), s)
                : s instanceof TypeExpr t ? declared(t.getType().asString(), s)
                : s instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr ? fieldDeclared(fa.getNameAsString(), s)
                : Optional.empty())
                .filter(ClassOrInterfaceType.class::isInstance).map(ClassOrInterfaceType.class::cast);
    }

    private Callees callees(Expression call) {
        try {
            ResolvedMethodDeclaration r = call instanceof MethodCallExpr mc ? mc.resolve() : ((MethodReferenceExpr) call).resolve();
            return new Callees(r.toAst(MethodDeclaration.class).map(d -> List.of(types.canonical(d))).orElse(List.of()), false);
        } catch (RuntimeException | StackOverflowError e) {
            // the solver fails on any signature naming a type outside the workspace (every Mono-returning method):
            // one method of that name and arity is still certain when the receiver's type itself was not a guess
            return byName(call);
        }
    }

    // shortcut: no @Qualifier/@Primary narrowing and no oneOf node, every implementation is followed; M4 adds both
    /** Implementations in the calling service or a library only: another service's class runs in that service. */
    private List<MethodDeclaration> implementations(MethodDeclaration m, Repo service) {
        return impls(m, service).stream().map(Impl::method).distinct().toList();
    }

    private List<Impl> impls(MethodDeclaration m, Repo service) {
        if (m.getBody().isPresent()) {
            return List.of(new Impl(m, null));
        }
        Optional<TypeDeclaration<?>> owner = m.getParentNode().filter(TypeDeclaration.class::isInstance).map(t -> (TypeDeclaration<?>) t);
        // declared in the class, else inherited from its nearest superclass declaring it (PingServiceImpl extends BasePing)
        List<Impl> impls = owner.map(o -> subtypes.getOrDefault(o, List.of())).orElse(List.of()).stream()
                .filter(c -> types.repo(c) == service || types.repo(c).kind() == Workspace.Kind.library)
                .flatMap(c -> Stream.concat(Stream.of(c), supertypes(c).stream())
                        .filter(t -> t instanceof ClassOrInterfaceDeclaration k && !k.isInterface())
                        .map(t -> declaring(t, m)).filter(l -> !l.isEmpty()).findFirst().orElse(List.of())
                        .stream().map(i -> new Impl(i, c)))
                .toList();
        if (impls.isEmpty() && owner.isPresent() && fragment(owner.get())) { // Spring Data's custom implementation, by name
            impls = types.bySimpleNameNear(owner.get().getNameAsString() + "Impl", owner.get()).stream()
                    .filter(c -> types.repo(c) == types.repo(owner.get()))
                    .filter(c -> types.repo(c) == service || types.repo(c).kind() == Workspace.Kind.library)
                    .flatMap(c -> c.getMethodsByName(m.getNameAsString()).stream()
                            .filter(i -> i.getBody().isPresent() && i.getParameters().size() == m.getParameters().size())
                            .map(i -> new Impl(i, c instanceof ClassOrInterfaceDeclaration k ? k : null))).toList();
        }
        return impls.isEmpty() ? List.of(new Impl(m, null)) : impls;
    }

    /**
     * {@code t}'s methods with a body implementing {@code m}: an overload of the same arity (top(String) beside
     * top(ServerRequest)) is not one; by arity only when none matches the parameter types as written (handle(Order) for handle(T)).
     */
    private static List<MethodDeclaration> declaring(TypeDeclaration<?> t, MethodDeclaration m) {
        List<MethodDeclaration> arity = t.getMethodsByName(m.getNameAsString()).stream()
                .filter(i -> i.getBody().isPresent() && i.getParameters().size() == m.getParameters().size())
                .toList();
        List<MethodDeclaration> same = arity.stream().filter(i -> paramTypes(i).equals(paramTypes(m))).toList();
        return same.isEmpty() ? arity : same;
    }

    /** Type variables {@code t}'s own extends/implements clauses bind (OrderController extends CrudController&lt;Order&gt;). */
    private Map<String, ArgValue> ownTypeArgs(TypeDeclaration<?> t, Map<String, ArgValue> args) {
        Map<String, ArgValue> out = new HashMap<>();
        if (t != null) {
            bindTypeArgs(t, List.of(), args, out, 0);
        }
        return out;
    }

    /**
     * A Spring Data repository (an interface reaching a Spring Data type through workspace interfaces) or a fragment
     * one extends: only for those does Spring look up {@code <Repository>Impl} / {@code <Fragment>Impl} by name.
     */
    private boolean fragment(TypeDeclaration<?> owner) {
        if (extenders == null) {
            extenders = new IdentityHashMap<>();
            allInterfaces.forEach(i -> i.getExtendedTypes().forEach(ext -> types.resolveExact(ext)
                    .ifPresent(t -> extenders.computeIfAbsent(t, k -> new ArrayList<>()).add(i))));
        }
        return owner instanceof ClassOrInterfaceDeclaration c && springRepository(c, 0)
                || extenders.getOrDefault(owner, List.of()).stream().anyMatch(i -> springRepository(i, 0));
    }

    private boolean springRepository(ClassOrInterfaceDeclaration i, int depth) {
        return depth <= 10 && i.getExtendedTypes().stream().anyMatch(ext -> types.resolveExact(ext)
                .map(t -> t instanceof ClassOrInterfaceDeclaration c && springRepository(c, depth + 1))
                .orElseGet(() -> Types.fromPackage(ext, SPRING_DATA)));
    }

    public static final String SPRING_DATA = "org.springframework.data.";

    /** Key in a walk's arguments of what a class's type variable stands for on this path, null for a method's own. */
    public static String typeArgKey(Node owner, String variable) {
        return owner instanceof TypeDeclaration<?> t ? t.getFullyQualifiedName().map(q -> "<" + q + ">" + variable).orElse(null) : null;
    }

    /** The class, interface or method declaring {@code t} as a type parameter; null when {@code t} names a type. */
    public static Node typeVariableOwner(ClassOrInterfaceType t) {
        if (t.getScope().isPresent() || t.getTypeArguments().isPresent()) {
            return null;
        }
        for (Node n = t; n != null; n = n.getParentNode().orElse(null)) {
            if (n instanceof com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<?> w
                    && w.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(t.getNameAsString()))) {
                return n;
            }
        }
        return null;
    }

    /**
     * What the callee's class type variables stand for on this path (see {@link #typeArgKey}): for {@code this}/no
     * receiver the caller's own; else from the receiver's declared type ({@code OrderSvc}, {@code CrudSvc<Order>})
     * and up its supertypes' clauses ({@code OrderSvc extends CrudSvc<Order>}: CrudSvc's E is Order). Value: the
     * entity's qualified name when it is a workspace type, else its name as written.
     */
    private Map<String, ArgValue> typeArgs(Expression call, ClassOrInterfaceType receiverType, Map<String, ArgValue> args) {
        Optional<Expression> scope = call instanceof MethodCallExpr mc ? mc.getScope() : Optional.of(((MethodReferenceExpr) call).getScope());
        Map<String, ArgValue> out = new HashMap<>();
        if (scope.isEmpty() || scope.get() instanceof ThisExpr || scope.get() instanceof com.github.javaparser.ast.expr.SuperExpr) {
            // the calling class's own clauses (OrderServiceImpl extends CrudSvc<Order>), then the caller's bindings
            enclosingType(call).ifPresent(t -> out.putAll(ownTypeArgs(t, args)));
            args.forEach((k, v) -> {
                if (k.startsWith("<")) {
                    out.put(k, v);
                }
            });
        } else if (receiverType != null) {
            types.resolve(receiverType).ifPresent(t -> bindTypeArgs(t, receiverType.getTypeArguments().map(List::<Type>copyOf).orElse(List.of()),
                    args, out, 0));
        }
        return out;
    }

    private void bindTypeArgs(TypeDeclaration<?> t, List<Type> written, Map<String, ArgValue> args, Map<String, ArgValue> out, int depth) {
        if (!(t instanceof ClassOrInterfaceDeclaration c) || depth > 10) {
            return;
        }
        for (int i = 0; i < Math.min(written.size(), c.getTypeParameters().size()); i++) {
            String key = typeArgKey(c, c.getTypeParameters().get(i).getNameAsString());
            ArgValue v = written.get(i) instanceof ClassOrInterfaceType ct ? typeValue(ct, args, out) : null;
            if (key != null && v != null) {
                out.put(key, v);
            }
        }
        Stream.concat(c.getExtendedTypes().stream(), c.getImplementedTypes().stream()).forEach(st -> types.resolve(st)
                .ifPresent(s -> bindTypeArgs(s, st.getTypeArguments().map(List::<Type>copyOf).orElse(List.of()), args, out, depth + 1)));
    }

    /** A type argument as bound: a type variable by what it stands for (in {@code out}, then the caller's), else the type. */
    private ArgValue typeValue(ClassOrInterfaceType ct, Map<String, ArgValue> args, Map<String, ArgValue> out) {
        Node owner = typeVariableOwner(ct);
        if (owner == null) {
            return new ArgValue(types.resolve(ct).flatMap(TypeDeclaration::getFullyQualifiedName).orElse(ct.getNameWithScope()), 0);
        }
        String key = typeArgKey(owner, ct.getNameAsString());
        return key == null ? null : out.getOrDefault(key, args.get(key));
    }

    /** Fallback when the solver fails: methods of the scope's type (and its supertypes) with that name and arity. */
    private Callees byName(Expression call) {
        String name;
        Optional<Expression> scope;
        int args;
        if (call instanceof MethodCallExpr mc) {
            name = mc.getNameAsString();
            scope = mc.getScope();
            args = mc.getArguments().size();
        } else {
            MethodReferenceExpr ref = (MethodReferenceExpr) call;
            name = ref.getIdentifier();
            scope = Optional.of(ref.getScope());
            args = -1; // a method reference does not show its arity
        }
        Optional<Typed> owner = scope.isEmpty() ? enclosingType(call).map(t -> new Typed(t, true)) : scopeType(scope.get());
        List<MethodDeclaration> ms = owner.stream().flatMap(o -> Stream.concat(Stream.of(o.type()), supertypes(o.type()).stream()))
                .flatMap(t -> t.getMethodsByName(name).stream())
                .filter(m -> args < 0 || m.getParameters().size() == args
                        || m.getParameters().stream().anyMatch(Parameter::isVarArgs))
                .distinct().toList();
        return new Callees(ms, ms.size() > 1 || !ms.isEmpty() && owner.filter(Typed::exact).isEmpty());
    }

    private Optional<Typed> scopeType(Expression scope) {
        if (scope instanceof ThisExpr) {
            return enclosingType(scope).map(t -> new Typed(t, true));
        }
        if (scope instanceof ObjectCreationExpr o) { // new Gateway(..).call(..)
            return typeOf(o.getType());
        }
        if (scope instanceof EnclosedExpr e) {
            return scopeType(e.getInner());
        }
        if (scope instanceof TypeExpr t) { // "inventory::release" parses as a type; a variable of that name wins
            return variableType(t.getType().asString(), scope).or(() -> typeOf(t.getType()));
        }
        if (scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
            return fieldType(fa.getNameAsString(), scope);
        }
        if (scope instanceof NameExpr n) {
            Optional<Typed> variable = variableType(n.getNameAsString(), scope);
            // not a variable: a static call on a class (UrlUtils.join), exact only through imports or the package
            return variable.isPresent() ? variable : types.byImports(n.getNameAsString(), scope).map(t -> new Typed(t, true))
                    .or(() -> types.bySimpleNameNear(n.getNameAsString(), scope).map(t -> new Typed(t, false)));
        }
        return Optional.empty();
    }

    /** Declared type of a parameter, local variable or field visible from {@code at}, by scope (see {@link Names}). */
    private Optional<Typed> variableType(String name, Node at) {
        return declared(name, at).flatMap(this::typeOf);
    }

    private Optional<Typed> fieldType(String name, Node at) {
        return fieldDeclared(name, at).flatMap(this::typeOf);
    }

    /** Declared type, as written, of a parameter, local variable or field visible from {@code at}. */
    private Optional<Type> declared(String name, Node at) {
        Optional<Node> decl = Names.declaration(name, at);
        if (decl.isEmpty() || decl.get() instanceof VariableDeclarator v && v.getParentNode().orElse(null) instanceof FieldDeclaration) {
            return fieldDeclared(name, at); // fields of supertypes too
        }
        return Optional.of(decl.get() instanceof Parameter p ? p.getType() : ((VariableDeclarator) decl.get()).getType());
    }

    private Optional<Type> fieldDeclared(String name, Node at) {
        Optional<TypeDeclaration<?>> owner = enclosingType(at);
        return owner.stream().flatMap(o -> Stream.concat(Stream.of(o), supertypes(o).stream()))
                .flatMap(t -> t.getFieldByName(name).stream())
                .findFirst().map(f -> f.getVariable(0).getType());
    }

    private Optional<Typed> typeOf(Type type) {
        if (!(type instanceof ClassOrInterfaceType ct)) {
            return Optional.empty();
        }
        return types.resolveExact(ct).map(t -> new Typed(t, true)).or(() -> types.resolve(ct).map(t -> new Typed(t, false)));
    }

    private static Optional<TypeDeclaration<?>> enclosingType(Node n) {
        return n.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t);
    }

    /** Transitive superclasses and interfaces found in the workspace. */
    private List<TypeDeclaration<?>> supertypes(TypeDeclaration<?> type) {
        Set<TypeDeclaration<?>> out = Collections.newSetFromMap(new IdentityHashMap<>());
        List<TypeDeclaration<?>> queue = new ArrayList<>(List.of(type));
        while (!queue.isEmpty()) {
            if (queue.remove(0) instanceof ClassOrInterfaceDeclaration c) {
                Set<ClassOrInterfaceType> direct = new LinkedHashSet<>(c.getExtendedTypes());
                direct.addAll(c.getImplementedTypes());
                for (ClassOrInterfaceType st : direct) {
                    types.resolve(st).filter(s -> s != type && out.add(s)).ifPresent(queue::add);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> paramTypes(MethodDeclaration m) {
        return m.getParameters().stream().map(p -> p.getType().asString()).toList();
    }

    private boolean library(Node n) {
        Repo r = types.repo(n);
        return r != null && r.kind() == Workspace.Kind.library;
    }

    private static final Set<String> RAW_CLIENTS = Set.of("webclient", "restclient", "resttemplate");

    private static String label(Node m) {
        if (m instanceof LambdaExpr) {
            return m.findAncestor(MethodDeclaration.class).map(FlowTracer::label).orElse("") + "$lambda";
        }
        return m.findAncestor(TypeDeclaration.class).map(t -> t.getNameAsString() + ".").orElse("") + ((MethodDeclaration) m).getNameAsString();
    }

    private String site(Expression call) {
        Node at = call instanceof MethodCallExpr mc ? mc.getName() : call;
        return types.relPath(call) + ":" + at.getBegin().map(p -> p.line + ":" + p.column).orElse("0");
    }

    private static int line(Expression call) {
        Node at = call instanceof MethodCallExpr mc ? mc.getName() : call;
        return at.getBegin().map(p -> p.line).orElse(0);
    }
}
