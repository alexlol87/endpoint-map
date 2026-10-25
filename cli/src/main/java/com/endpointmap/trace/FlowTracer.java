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
 * Calls the symbol solver can't resolve fall back to name + arity matching and are marked low confidence.
 */
public final class FlowTracer {

    /** An outbound call reached from a method: {@code via} lists every method entered on the way, client method last. */
    public record Found(OutboundCall call, List<String> via, String file, int line, boolean lowConfidence) {
    }

    private record Callees(List<MethodDeclaration> methods, boolean low) {
    }

    private record Result(List<Found> found, boolean cut) {
    }

    private final Types types;
    private final List<OutboundDetector> detectors;
    private final Map<TypeDeclaration<?>, List<ClassOrInterfaceDeclaration>> subtypes = new IdentityHashMap<>();
    // per service: detectors resolve placeholders with the calling service's config, so library results differ
    private final Map<Repo, Map<MethodDeclaration, List<Found>>> memo = new IdentityHashMap<>();

    public FlowTracer(Workspace ws, Types types, List<OutboundDetector> detectors) {
        this.types = types;
        this.detectors = detectors;
        ws.repos().stream().flatMap(r -> r.sources().stream())
                .flatMap(sf -> sf.cu().findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(c -> !c.isInterface())
                .forEach(c -> supertypes(c).forEach(s -> subtypes.computeIfAbsent(s, k -> new ArrayList<>()).add(c)));
    }

    public List<Found> trace(MethodDeclaration handler, Repo service, Map<String, String> config) {
        return walk(handler, service, config, Collections.newSetFromMap(new IdentityHashMap<>())).found();
    }

    private Result walk(MethodDeclaration m, Repo service, Map<String, String> config, Set<MethodDeclaration> stack) {
        Map<MethodDeclaration, List<Found>> done = memo.computeIfAbsent(service, k -> new IdentityHashMap<>());
        if (done.containsKey(m)) {
            return new Result(done.get(m), false);
        }
        if (m.getBody().isEmpty()) {
            return new Result(List.of(), false);
        }
        stack.add(m);
        List<Found> found = new ArrayList<>();
        boolean cut = false;
        for (Expression call : m.getBody().get().findAll(Expression.class,
                e -> e instanceof MethodCallExpr || e instanceof MethodReferenceExpr)) {
            Callees c = callees(call);
            CallSite site = new CallSite(call, c.methods(), service, config);
            List<OutboundCall> out = detectors.stream().flatMap(d -> d.detect(site).stream()).toList();
            if (!out.isEmpty()) {
                List<String> via = c.methods().isEmpty() ? List.of() : List.of(label(c.methods().get(0)));
                out.forEach(o -> found.add(new Found(o, via, types.relPath(call), line(call), c.low())));
                continue;
            }
            for (MethodDeclaration callee : c.methods().stream().flatMap(d -> implementations(d).stream()).distinct().toList()) {
                if (stack.contains(callee)) {
                    cut = true; // recursion: the branch is already being walked higher up
                    continue;
                }
                Result r = walk(callee, service, config, stack);
                cut |= r.cut();
                for (Found f : r.found()) {
                    List<String> via = new ArrayList<>(List.of(label(callee)));
                    via.addAll(f.via());
                    found.add(new Found(f.call(), List.copyOf(via), f.file(), f.line(), f.lowConfidence() || c.low()));
                }
            }
        }
        stack.remove(m);
        if (!cut) { // a result cut short by recursion is only complete for the caller that cut it
            done.put(m, found);
        }
        return new Result(found, cut);
    }

    private Callees callees(Expression call) {
        try {
            ResolvedMethodDeclaration r = call instanceof MethodCallExpr mc ? mc.resolve() : ((MethodReferenceExpr) call).resolve();
            return new Callees(r.toAst(MethodDeclaration.class).map(d -> List.of(types.canonical(d))).orElse(List.of()), false);
        } catch (RuntimeException | StackOverflowError e) {
            List<MethodDeclaration> byName = byName(call);
            return new Callees(byName, !byName.isEmpty());
        }
    }

    // shortcut: no @Qualifier/@Primary narrowing and no oneOf node, every implementation is followed; M4 adds both
    private List<MethodDeclaration> implementations(MethodDeclaration m) {
        if (m.getBody().isPresent()) {
            return List.of(m);
        }
        Optional<TypeDeclaration<?>> owner = m.getParentNode().filter(TypeDeclaration.class::isInstance).map(t -> (TypeDeclaration<?>) t);
        List<MethodDeclaration> impls = owner.map(o -> subtypes.getOrDefault(o, List.of())).orElse(List.of()).stream()
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
