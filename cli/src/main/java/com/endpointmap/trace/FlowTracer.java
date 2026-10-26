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
     * (PLAN: client "&lt;wrapper class&gt;"); null otherwise.
     */
    public record Found(OutboundCall call, List<String> via, String file, int line, boolean lowConfidence, List<String> sites,
                        String wrapper) {
    }

    private record Callees(List<MethodDeclaration> methods, boolean low) {
    }

    /** A receiver's static type; {@code exact} when found by the solver, a scoped declaration or Java's import rules. */
    private record Typed(TypeDeclaration<?> type, boolean exact) {
    }

    /** {@code backTo}: stack depth of the shallowest method a recursive call jumped back to, MAX_VALUE if none. */
    private record Result(List<Found> found, int backTo) {
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
    // per service: detectors resolve placeholders with the calling service's config, so library results differ;
    // per argument values: a util called with another WebClient bean or URL reaches another target
    private final Map<Repo, Map<MethodDeclaration, Map<Map<String, ArgValue>, List<Found>>>> memo = new IdentityHashMap<>();

    public FlowTracer(Workspace ws, Types types, List<OutboundDetector> detectors) {
        this(ws, types, detectors, ArgumentBinder.NONE);
    }

    public FlowTracer(Workspace ws, Types types, List<OutboundDetector> detectors, ArgumentBinder binder) {
        this.types = types;
        this.detectors = detectors;
        this.binder = binder;
        ws.repos().stream().flatMap(r -> r.sources().stream())
                .flatMap(sf -> sf.cu().findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(c -> !c.isInterface())
                .forEach(c -> supertypes(c).forEach(s -> subtypes.computeIfAbsent(s, k -> new ArrayList<>()).add(c)));
    }

    /**
     * Distinct URLs one call path (the same chain of call sites) may give a handler; more is one callSites entry.
     * Argument combinations multiply along one path; separate calls through a shared helper each keep their edge.
     */
    public static final int MAX_URLS_PER_CALL_SITE = 4;

    /** Methods walked per handler before cut-short recursive results are reused whatever their arguments. */
    static final int MAX_WALKS = 100;
    private int walks;

    public List<Found> trace(MethodDeclaration handler, Repo service, Map<String, String> config) {
        walks = 0;
        List<Found> all = walk(handler, Map.of(), service, config, new IdentityHashMap<>(), new IdentityHashMap<>()).found();
        // past the walk budget recursive results were reused across arguments: their URLs are capped per client call
        // site, not per path, so a mixed-up pairing never stands as a confident edge
        boolean over = walks > MAX_WALKS;
        Map<List<String>, List<Found>> byPath = new LinkedHashMap<>();
        all.forEach(f -> byPath.computeIfAbsent(over ? List.of(f.sites().get(f.sites().size() - 1)) : f.sites(),
                k -> new ArrayList<>()).add(f));
        Map<Key, Found> out = new LinkedHashMap<>(); // several capped paths to one site give the same {*} edge
        for (List<Found> site : byPath.values()) {
            if (site.size() <= MAX_URLS_PER_CALL_SITE) {
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
                    first.line(), first.lowConfidence(), first.sites(), first.wrapper()));
        }
        return List.copyOf(out.values());
    }

    /**
     * {@code stack} maps each method being walked to its depth. {@code partial} holds results cut short by a recursive
     * call back to a method still on the stack: valid until that method returns, so a cycle is walked once, not once
     * per path into it. {@code args}: what the caller passed to m's parameters.
     */
    private Result walk(MethodDeclaration m, Map<String, ArgValue> args, Repo service, Map<String, String> config,
                        Map<MethodDeclaration, Integer> stack, Map<MethodDeclaration, Map<Map<String, ArgValue>, Result>> partial) {
        Map<Map<String, ArgValue>, List<Found>> done = memo.computeIfAbsent(service, k -> new IdentityHashMap<>())
                .computeIfAbsent(m, k -> new HashMap<>());
        if (done.containsKey(args)) {
            return new Result(done.get(args), Integer.MAX_VALUE);
        }
        Map<Map<String, ArgValue>, Result> cuts = partial.getOrDefault(m, Map.of());
        // a cut-short result holds for the same arguments only; past the budget any one is reused, else every simple
        // path through a mutually recursive cluster is walked (exponential)
        Result cut = cuts.get(args) != null || walks <= MAX_WALKS ? cuts.get(args) : cuts.values().stream().findFirst().orElse(null);
        if (cut != null) {
            return cut;
        }
        walks++;
        if (m.getBody().isEmpty()) {
            return new Result(List.of(), Integer.MAX_VALUE);
        }
        int depth = stack.size();
        stack.put(m, depth);
        Map<Key, Found> found = new LinkedHashMap<>();
        int backTo = Integer.MAX_VALUE;
        // shortcut: the body is walked flat (no if/loop/try structure); the flow tree (#13) needs that structure
        for (Expression call : m.getBody().get().findAll(Expression.class,
                e -> e instanceof MethodCallExpr || e instanceof MethodReferenceExpr)) {
            // the same in every walk of m: a recursive cluster walks m once per argument combination
            Callees c = callees.computeIfAbsent(call, this::callees);
            List<TypeDeclaration<?>> receiver = receivers.computeIfAbsent(call, this::receiverTypes);
            boolean classified = false;
            // one detection per callee, so via names the right one; a framework call (WebClient...) has none
            for (MethodDeclaration callee : c.methods().isEmpty() ? Collections.<MethodDeclaration>singletonList(null) : c.methods()) {
                List<OutboundCall> out = detectors.stream().flatMap(d -> d.detect(new CallSite(call,
                        callee == null ? List.of() : List.of(callee), receiver, service, config, m, args)).stream()).toList();
                for (OutboundCall o : out) {
                    // an abstract method is named as the caller sees it: ItemClient.get, not the inherited ItemApi.get
                    String label = o.label() != null ? o.label() : receiver.isEmpty() || callee.getBody().isPresent()
                            ? label(callee) : receiver.get(0).getNameAsString() + "." + callee.getNameAsString();
                    String wrapper = RAW_CLIENTS.contains(o.client()) && library(call)
                            ? enclosingType(call).map(TypeDeclaration::getNameAsString).orElse(null) : null;
                    add(found, new Found(o, List.of(label), types.relPath(call), line(call), c.low(), List.of(site(call)), wrapper));
                }
                classified |= !out.isEmpty();
            }
            if (classified) {
                continue;
            }
            for (MethodDeclaration callee : c.methods().stream().flatMap(d -> implementations(d, service).stream()).distinct().toList()) {
                Integer at = stack.get(callee);
                if (at != null) {
                    backTo = Math.min(backTo, at); // recursion: skip only this back edge
                    continue;
                }
                List<Map<String, ArgValue>> bound = call instanceof MethodCallExpr mc
                        ? binder.bind(callee, mc, new CallSite(call, c.methods(), receiver, service, config, m, args)) : List.of(Map.of());
                for (Map<String, ArgValue> calleeArgs : bound) {
                    Result r = walk(callee, calleeArgs, service, config, stack, partial);
                    backTo = Math.min(backTo, r.backTo());
                    for (Found f : r.found()) {
                        List<String> via = new ArrayList<>(List.of(label(callee)));
                        via.addAll(f.via());
                        List<String> sites = new ArrayList<>(List.of(site(call)));
                        sites.addAll(f.sites());
                        // entering library code from the service's: that library class is the client the service calls
                        String wrapper = f.wrapper() != null && library(callee) && !library(m)
                                ? enclosingType(callee).map(TypeDeclaration::getNameAsString).orElse(f.wrapper()) : f.wrapper();
                        add(found, new Found(f.call(), List.copyOf(via), f.file(), f.line(), f.lowConfidence() || c.low(),
                                List.copyOf(sites), wrapper));
                    }
                }
            }
        }
        stack.remove(m);
        partial.values().forEach(byArgs -> byArgs.values().removeIf(r -> r.backTo() >= depth)); // they were cut back to m or below, which is done now
        Result result = new Result(List.copyOf(found.values()), backTo);
        if (backTo >= depth) { // complete: any recursion below came back to m itself, not to a caller still being walked
            done.put(args, result.found());
            return new Result(result.found(), Integer.MAX_VALUE);
        }
        partial.computeIfAbsent(m, k -> new LinkedHashMap<>()).put(args, result);
        return result;
    }

    /**
     * A certain path beats a low-confidence one, then the shorter {@code via} wins (a memoized result reached through
     * a cycle can carry a longer, looping chain), then the first found.
     */
    private static void add(Map<Key, Found> found, Found f) {
        found.merge(new Key(f.call(), f.file(), f.line()), f, (a, b) -> a.lowConfidence() != b.lowConfidence()
                ? (a.lowConfidence() ? b : a) : b.via().size() < a.via().size() ? b : a);
    }

    /** Static type of the call's receiver and its supertypes; empty when it has no scope or it can't be told. */
    private List<TypeDeclaration<?>> receiverTypes(Expression call) {
        Optional<Expression> scope = call instanceof MethodCallExpr mc ? mc.getScope()
                : Optional.of(((MethodReferenceExpr) call).getScope());
        return scope.flatMap(this::scopeType).map(Typed::type)
                .map(t -> Stream.concat(Stream.of(t), supertypes(t).stream()).toList()).orElse(List.of());
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
        if (m.getBody().isPresent()) {
            return List.of(m);
        }
        Optional<TypeDeclaration<?>> owner = m.getParentNode().filter(TypeDeclaration.class::isInstance).map(t -> (TypeDeclaration<?>) t);
        List<MethodDeclaration> impls = owner.map(o -> subtypes.getOrDefault(o, List.of())).orElse(List.of()).stream()
                .filter(c -> types.repo(c) == service || types.repo(c).kind() == Workspace.Kind.library)
                .flatMap(c -> c.getMethodsByName(m.getNameAsString()).stream())
                .filter(i -> i.getBody().isPresent() && i.getParameters().size() == m.getParameters().size())
                .toList();
        return impls.isEmpty() ? List.of(m) : impls;
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
        Optional<Node> decl = Names.declaration(name, at);
        if (decl.isEmpty() || decl.get() instanceof VariableDeclarator v && v.getParentNode().orElse(null) instanceof FieldDeclaration) {
            return fieldType(name, at); // fields of supertypes too
        }
        return typeOf(decl.get() instanceof Parameter p ? p.getType() : ((VariableDeclarator) decl.get()).getType());
    }

    private Optional<Typed> fieldType(String name, Node at) {
        Optional<TypeDeclaration<?>> owner = enclosingType(at);
        return owner.stream().flatMap(o -> Stream.concat(Stream.of(o), supertypes(o).stream()))
                .flatMap(t -> t.getFieldByName(name).stream())
                .findFirst().flatMap(f -> typeOf(f.getVariable(0).getType()));
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

    private boolean library(Node n) {
        Repo r = types.repo(n);
        return r != null && r.kind() == Workspace.Kind.library;
    }

    private static final Set<String> RAW_CLIENTS = Set.of("webclient", "restclient", "resttemplate");

    private static String label(MethodDeclaration m) {
        return m.findAncestor(TypeDeclaration.class).map(t -> t.getNameAsString() + ".").orElse("") + m.getNameAsString();
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
