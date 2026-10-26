package com.endpointmap.trace;

import com.endpointmap.workspace.Names;
import com.endpointmap.workspace.Workspace;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Which implementations of an interface can be injected at a call's receiver, as Spring picks them: classes whose
 * {@code @ConditionalOnProperty} fails for the service's config are dropped; then for an injected field or
 * constructor parameter its {@code @Qualifier}, else {@code @Primary}, else a bean named like the field/parameter.
 * A class's bean names: its stereotype value or decapitalized name, its {@code @Qualifier}, and those of
 * {@code @Bean} methods creating it ({@code return new SmsNotifier(url)}). Nothing matching keeps all of them.
 */
final class Beans {

    private record BeanMethod(MethodDeclaration method, Set<String> names, boolean primary) {
    }

    /** Stereotypes by simple name; workspace annotations meta-annotated with one ({@code @UseCase}) are added. */
    private final Set<String> stereotypes = new HashSet<>(Set.of("Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "ControllerAdvice", "RestControllerAdvice", "Named"));

    private final List<BeanMethod> beanMethods = new ArrayList<>();

    Beans(Workspace ws) {
        List<com.github.javaparser.ast.body.AnnotationDeclaration> annotations = ws.repos().stream().flatMap(r -> r.sources().stream())
                .flatMap(sf -> sf.cu().findAll(com.github.javaparser.ast.body.AnnotationDeclaration.class).stream()).toList();
        for (boolean grew = true; grew; ) { // to a fixpoint: meta-annotations of meta-annotations
            grew = false;
            for (var a : annotations) {
                if (a.getAnnotations().stream().anyMatch(x -> stereotypes.contains(x.getName().getIdentifier()))) {
                    grew |= stereotypes.add(a.getNameAsString());
                }
            }
        }
        ws.repos().stream().flatMap(r -> r.sources().stream()).flatMap(sf -> sf.cu().findAll(MethodDeclaration.class).stream())
                .filter(m -> Workspace.hasAnnotation(m, "Bean")).forEach(m -> {
                    Set<String> names = new HashSet<>(values(m, "Bean", "value", "name"));
                    if (names.isEmpty()) {
                        names.add(m.getNameAsString());
                    }
                    names.addAll(values(m, "Qualifier", "value"));
                    beanMethods.add(new BeanMethod(m, names, Workspace.hasAnnotation(m, "Primary")));
                });
    }

    /** {@code classes}: the implementing class of each candidate; returns the ones kept, never none. */
    List<ClassOrInterfaceDeclaration> narrow(Optional<Expression> scope, List<ClassOrInterfaceDeclaration> classes, Workspace.Repo service,
                                             Map<String, String> config, Function<Node, Workspace.Repo> repo) {
        List<ClassOrInterfaceDeclaration> active = classes.stream().filter(c -> conditionHolds(c, config)).toList();
        List<ClassOrInterfaceDeclaration> out = active.isEmpty() ? classes : active;
        Optional<NodeWithAnnotations<?>> point = scope.flatMap(Beans::injectionPoint);
        if (point.isEmpty() || out.size() < 2) {
            return out;
        }
        List<BeanMethod> visible = beanMethods.stream().filter(b -> repo.apply(b.method()) == service
                || repo.apply(b.method()) != null && repo.apply(b.method()).kind() == Workspace.Kind.library).toList();
        List<String> q = values(point.get(), "Qualifier", "value");
        List<ClassOrInterfaceDeclaration> hit = !q.isEmpty() ? out.stream().filter(c -> names(c, visible).contains(q.get(0))).toList()
                : out.stream().filter(c -> primary(c, visible)).toList();
        if (q.isEmpty() && hit.isEmpty()) { // Spring's last resort: the bean named like the injection point
            String name = point.get() instanceof Parameter p ? p.getNameAsString()
                    : ((FieldDeclaration) point.get()).getVariable(0).getNameAsString();
            hit = out.stream().filter(c -> names(c, visible).contains(name)).toList();
        }
        return hit.isEmpty() ? out : hit;
    }

    /**
     * The field or constructor parameter the receiver was injected through: a field ({@code this.x = x} in a
     * constructor gives the parameter, whose {@code @Qualifier} counts) or a constructor parameter itself.
     */
    private static Optional<NodeWithAnnotations<?>> injectionPoint(Expression scope) {
        String name = scope instanceof NameExpr n ? n.getNameAsString()
                : scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr ? fa.getNameAsString() : null;
        Optional<Node> decl = name == null ? Optional.empty() : Names.declaration(name, scope);
        if (decl.isPresent() && decl.get() instanceof Parameter p) {
            return p.getParentNode().filter(ConstructorDeclaration.class::isInstance).map(x -> p);
        }
        if (decl.isEmpty() || !(decl.get() instanceof VariableDeclarator v) || !(v.getParentNode().orElse(null) instanceof FieldDeclaration f)) {
            return Optional.empty();
        }
        if (Workspace.hasAnnotation(f, "Qualifier")) {
            return Optional.of(f);
        }
        for (ConstructorDeclaration c : f.findAncestor(ClassOrInterfaceDeclaration.class).map(ClassOrInterfaceDeclaration::getConstructors).orElse(List.of())) {
            for (AssignExpr a : c.findAll(AssignExpr.class)) {
                boolean self = a.getTarget() instanceof FieldAccessExpr t && t.getScope() instanceof ThisExpr && t.getNameAsString().equals(name)
                        || a.getTarget() instanceof NameExpr tn && tn.getNameAsString().equals(name);
                if (self && a.getValue() instanceof NameExpr p) {
                    Optional<Parameter> param = c.getParameterByName(p.getNameAsString());
                    if (param.isPresent()) {
                        return Optional.of(param.get());
                    }
                }
            }
        }
        return Optional.of(f);
    }

    private Set<String> names(ClassOrInterfaceDeclaration c, List<BeanMethod> visible) {
        Set<String> out = new HashSet<>(values(c, "Qualifier", "value"));
        c.getAnnotations().stream().filter(a -> stereotypes.contains(a.getName().getIdentifier()))
                .forEach(a -> out.addAll(values(a, "value")));
        String n = c.getNameAsString(); // Spring's default for a scanned class: PushNotifier -> pushNotifier, URLApi stays
        if (c.getAnnotations().stream().anyMatch(a -> stereotypes.contains(a.getName().getIdentifier()))) out.add(n.length() > 1 && Character.isUpperCase(n.charAt(1)) ? n : Character.toLowerCase(n.charAt(0)) + n.substring(1));
        creators(c, visible).forEach(b -> out.addAll(b.names()));
        return out;
    }

    private static boolean primary(ClassOrInterfaceDeclaration c, List<BeanMethod> visible) {
        return Workspace.hasAnnotation(c, "Primary") || creators(c, visible).stream().anyMatch(BeanMethod::primary);
    }

    private static List<BeanMethod> creators(ClassOrInterfaceDeclaration c, List<BeanMethod> visible) {
        return visible.stream().filter(b -> creates(b.method(), c.getNameAsString())).toList();
    }

    /**
     * {@code m} returns a new {@code x}: its declared return type is {@code x}, or every return is {@code new x(..)} or a
     * local initialized/assigned with one. When a return can't be told (a builder call, a parameter), any {@code new x(..)}
     * in the body counts, as a {@code new FooInterceptor()} inside a RestTemplate @Bean is rarer than a lost @Primary.
     */
    static boolean creates(MethodDeclaration m, String x) {
        if (m.getType().isClassOrInterfaceType() && m.getType().asClassOrInterfaceType().getNameAsString().equals(x)) {
            return true;
        }
        List<Expression> returned = m.findAll(com.github.javaparser.ast.stmt.ReturnStmt.class).stream()
                .flatMap(r -> r.getExpression().stream()).toList();
        List<String> made = new ArrayList<>();
        for (Expression r : returned) {
            List<Expression> sources = new ArrayList<>(List.of(r));
            if (r instanceof NameExpr n) {
                Optional<Node> d = Names.declaration(n.getNameAsString(), r);
                if (d.isPresent() && d.get() instanceof VariableDeclarator v) {
                    sources.clear();
                    v.getInitializer().ifPresent(sources::add);
                    m.findAll(AssignExpr.class, a -> a.getTarget() instanceof NameExpr t && t.getNameAsString().equals(n.getNameAsString()))
                            .forEach(a -> sources.add(a.getValue()));
                }
            }
            if (sources.isEmpty() || !sources.stream().allMatch(ObjectCreationExpr.class::isInstance)) {
                return m.findAll(ObjectCreationExpr.class).stream().anyMatch(o -> o.getType().getNameAsString().equals(x));
            }
            sources.forEach(o -> made.add(((ObjectCreationExpr) o).getType().getNameAsString()));
        }
        return made.contains(x);
    }

    /** {@code @ConditionalOnProperty} on the class, checked against the service's config (prefix, name/value, havingValue, matchIfMissing). */
    // shortcut: only on the class itself, not on @Bean methods, @Profile or other conditions; add those when a fixture needs them
    static boolean conditionHolds(ClassOrInterfaceDeclaration c, Map<String, String> config) {
        Optional<AnnotationExpr> a = c.getAnnotationByName("ConditionalOnProperty");
        if (a.isEmpty()) {
            return true;
        }
        List<String> prefix = values(a.get(), "prefix");
        List<String> having = values(a.get(), "havingValue");
        boolean ifMissing = member(a.get(), "matchIfMissing").map(e -> e.toString().equals("true")).orElse(false);
        List<String> keys = new ArrayList<>(values(a.get(), "name"));
        keys.addAll(values(a.get(), "value"));
        for (String key : keys) {
            String full = prefix.isEmpty() || prefix.get(0).isEmpty() ? key : prefix.get(0).replaceAll("\\.$", "") + "." + key;
            String v = config.get(full);
            boolean ok = v == null ? ifMissing : having.isEmpty() || having.get(0).isEmpty() ? !v.equalsIgnoreCase("false")
                    : v.equalsIgnoreCase(having.get(0));
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // shortcut: string literals only (no constants in annotation values); use ConstantEvaluator when a fixture needs it
    private static List<String> values(NodeWithAnnotations<?> n, String annotation, String... members) {
        List<String> out = new ArrayList<>();
        n.getAnnotationByName(annotation).ifPresent(a -> out.addAll(values(a, members)));
        return out;
    }

    private static List<String> values(AnnotationExpr a, String... members) {
        List<String> out = new ArrayList<>();
        for (String m : members) {
            member(a, m).ifPresent(v -> (v.isArrayInitializerExpr() ? v.asArrayInitializerExpr().getValues() : List.of(v)).stream()
                    .filter(StringLiteralExpr.class::isInstance).forEach(s -> out.add(((StringLiteralExpr) s).getValue())));
        }
        return out;
    }

    private static Optional<Expression> member(AnnotationExpr a, String name) {
        if (a instanceof SingleMemberAnnotationExpr s) {
            return name.equals("value") ? Optional.of(s.getMemberValue()) : Optional.empty();
        }
        return a instanceof NormalAnnotationExpr n
                ? n.getPairs().stream().filter(p -> p.getNameAsString().equals(name)).map(p -> p.getValue()).findFirst() : Optional.empty();
    }
}
