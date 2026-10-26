package com.endpointmap.trace;

import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.util.ArrayList;
import java.util.Collections;
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
 * Calls the symbol solver can't resolve fall back to name + arity matching, low confidence when several match.
 * Each method keeps one result per call site and target, so repeated calls and diamonds don't multiply paths.
 */
public final class FlowTracer {

    /** An outbound call reached from a method: {@code via} lists every method entered on the way, client method last. */
    public record Found(OutboundCall call, List<String> via, String file, int line, boolean lowConfidence) {
    }

    private record Callees(List<MethodDeclaration> methods, boolean low) {
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

    public List<Found> trace(MethodDeclaration handler, Repo service, Map<String, String> config) {
        return walk(handler, Map.of(), service, config, new IdentityHashMap<>(), new IdentityHashMap<>()).found();
    }

    /**
     * {@code stack} maps each method being walked to its depth. {@code partial} holds results cut short by a recursive
     * call back to a method still on the stack: valid until that method returns, so a cycle is walked once, not once
     * per path into it. {@code args}: what the caller passed to m's parameters.
     */
    private Result walk(MethodDeclaration m, Map<String, ArgValue> args, Repo service, Map<String, String> config,
                        Map<MethodDeclaration, Integer> stack, Map<MethodDeclaration, Result> partial) {
        Map<Map<String, ArgValue>, List<Found>> done = memo.computeIfAbsent(service, k -> new IdentityHashMap<>())
                .computeIfAbsent(m, k -> new java.util.HashMap<>());
        if (done.containsKey(args)) {
            return new Result(done.get(args), Integer.MAX_VALUE);
        }
        // shortcut: a cut-short result is reused whatever the arguments; only matters for a cycle walked with two
        // different argument values at once, key partial by args too if that shows up
        if (partial.containsKey(m)) {
            return partial.get(m);
        }
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
            Callees c = callees(call);
            List<TypeDeclaration<?>> receiver = receiverTypes(call);
            boolean classified = false;
            // one detection per callee, so via names the right one; a framework call (WebClient...) has none
            for (MethodDeclaration callee : c.methods().isEmpty() ? java.util.Collections.<MethodDeclaration>singletonList(null) : c.methods()) {
                List<OutboundCall> out = detectors.stream().flatMap(d -> d.detect(new CallSite(call,
                        callee == null ? List.of() : List.of(callee), receiver, service, config, m, args)).stream()).toList();
                for (OutboundCall o : out) {
                    // an abstract method is named as the caller sees it: ItemClient.get, not the inherited ItemApi.get
                    String label = o.label() != null ? o.label() : receiver.isEmpty() || callee.getBody().isPresent()
                            ? label(callee) : receiver.get(0).getNameAsString() + "." + callee.getNameAsString();
                    add(found, new Found(o, List.of(label), types.relPath(call), line(call), c.low()));
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
                Map<String, ArgValue> calleeArgs = call instanceof MethodCallExpr mc
                        ? binder.bind(callee, mc, new CallSite(call, c.methods(), receiver, service, config, m, args)) : Map.of();
                Result r = walk(callee, calleeArgs, service, config, stack, partial);
                backTo = Math.min(backTo, r.backTo());
                for (Found f : r.found()) {
                    List<String> via = new ArrayList<>(List.of(label(callee)));
                    via.addAll(f.via());
                    add(found, new Found(f.call(), List.copyOf(via), f.file(), f.line(), f.lowConfidence() || c.low()));
                }
            }
        }
        stack.remove(m);
        partial.values().removeIf(r -> r.backTo() >= depth); // they were cut back to m or below, which is done now
        Result result = new Result(List.copyOf(found.values()), backTo);
        if (backTo >= depth) { // complete: any recursion below came back to m itself, not to a caller still being walked
            done.put(args, result.found());
            return new Result(result.found(), Integer.MAX_VALUE);
        }
        partial.put(m, result);
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
        return scope.flatMap(this::scopeType)
                .map(t -> Stream.concat(Stream.of(t), supertypes(t).stream()).toList()).orElse(List.of());
    }

    private Callees callees(Expression call) {
        try {
            ResolvedMethodDeclaration r = call instanceof MethodCallExpr mc ? mc.resolve() : ((MethodReferenceExpr) call).resolve();
            return new Callees(r.toAst(MethodDeclaration.class).map(d -> List.of(types.canonical(d))).orElse(List.of()), false);
        } catch (RuntimeException | StackOverflowError e) {
            // the solver fails on any signature naming a type outside the workspace (every Mono-returning method):
            // one method of that name and arity on the receiver's declared type is still certain
            List<MethodDeclaration> byName = byName(call);
            return new Callees(byName, byName.size() > 1);
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
    private List<MethodDeclaration> byName(Expression call) {
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
        Optional<TypeDeclaration<?>> owner = scope.isEmpty() ? enclosingType(call) : scopeType(scope.get());
        return owner.stream().flatMap(o -> Stream.concat(Stream.of(o), supertypes(o).stream()))
                .flatMap(t -> t.getMethodsByName(name).stream())
                .filter(m -> args < 0 || m.getParameters().size() == args
                        || m.getParameters().stream().anyMatch(Parameter::isVarArgs))
                .distinct().toList();
    }

    private Optional<TypeDeclaration<?>> scopeType(Expression scope) {
        if (scope instanceof ThisExpr) {
            return enclosingType(scope);
        }
        if (scope instanceof TypeExpr t) { // "inventory::release" parses as a type; a variable of that name wins
            return variableType(t.getType().asString(), scope).or(() -> typeOf(t.getType()));
        }
        if (scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
            return fieldType(fa.getNameAsString(), scope);
        }
        if (scope instanceof NameExpr n) {
            Optional<TypeDeclaration<?>> variable = variableType(n.getNameAsString(), scope);
            // not a variable: a static call on a class (UrlUtils.join)
            return variable.isPresent() ? variable : types.bySimpleNameNear(n.getNameAsString(), scope);
        }
        return Optional.empty();
    }

    /** Declared type of a parameter, local variable or field visible from {@code at}, innermost first. */
    private Optional<TypeDeclaration<?>> variableType(String name, Node at) {
        for (Node n = at; n != null; n = n.getParentNode().orElse(null)) {
            List<Parameter> params = n instanceof CallableDeclaration<?> cd ? cd.getParameters()
                    : n instanceof LambdaExpr l ? l.getParameters() : List.of();
            for (Parameter p : params) {
                if (p.getNameAsString().equals(name)) {
                    return typeOf(p.getType());
                }
            }
            if (n instanceof CallableDeclaration<?>) {
                for (VariableDeclarator v : n.findAll(VariableDeclarator.class)) {
                    if (v.getNameAsString().equals(name) && !(v.getParentNode().orElse(null) instanceof FieldDeclaration)) {
                        return typeOf(v.getType());
                    }
                }
            }
            if (n instanceof TypeDeclaration<?>) {
                return fieldType(name, at);
            }
        }
        return Optional.empty();
    }

    private Optional<TypeDeclaration<?>> fieldType(String name, Node at) {
        Optional<TypeDeclaration<?>> owner = enclosingType(at);
        return owner.stream().flatMap(o -> Stream.concat(Stream.of(o), supertypes(o).stream()))
                .flatMap(t -> t.getFieldByName(name).stream())
                .findFirst().flatMap(f -> typeOf(f.getVariable(0).getType()));
    }

    private Optional<TypeDeclaration<?>> typeOf(Type type) {
        return type instanceof ClassOrInterfaceType ct ? types.resolve(ct) : Optional.empty();
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

    private static String label(MethodDeclaration m) {
        return m.findAncestor(TypeDeclaration.class).map(t -> t.getNameAsString() + ".").orElse("") + m.getNameAsString();
    }

    private static int line(Expression call) {
        Node at = call instanceof MethodCallExpr mc ? mc.getName() : call;
        return at.getBegin().map(p -> p.line).orElse(0);
    }
}
