package com.endpointmap.outbound;

import com.endpointmap.endpoints.ConstantEvaluator;
import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.workspace.Types;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A call on a Spring Data repository: an interface extending one of Spring's repository interfaces, directly or
 * through workspace interfaces (a library's base repository). Methods only Spring declares (save, findById...) are
 * matched by name on the receiver's type. Table from the entity (the first generic argument): {@code @Table(name)}
 * (JPA or spring.data.relational), else {@code @Entity(name)}, else the class name in snake_case, as Spring Boot's
 * default naming (Hibernate's CamelCaseToUnderscoresNamingStrategy, R2DBC's DefaultNamingStrategy) maps it.
 */
public final class RepositoryDetector implements OutboundDetector {

    private static final Map<String, String> SPRING = Map.of("Repository", "jpa", "CrudRepository", "jpa",
            "ListCrudRepository", "jpa", "PagingAndSortingRepository", "jpa", "ListPagingAndSortingRepository", "jpa",
            "JpaRepository", "jpa", "ReactiveCrudRepository", "r2dbc", "ReactiveSortingRepository", "r2dbc",
            "R2dbcRepository", "r2dbc");
    private static final Map<String, List<String>> OPS = Map.of(
            "read", List.of("find", "get", "read", "query", "count", "exists", "stream", "search"),
            "write", List.of("save", "insert", "update", "flush"),
            "delete", List.of("delete", "remove"));

    private record Base(String tech, ClassOrInterfaceType entity) {
    }

    private final Types types;
    private final ConstantEvaluator constants;

    public RepositoryDetector(Types types, ConstantEvaluator constants) {
        this.types = types;
        this.constants = constants;
    }

    @Override
    public List<OutboundCall> detect(CallSite site) {
        String name = site.call() instanceof MethodCallExpr mc ? mc.getNameAsString()
                : site.call() instanceof MethodReferenceExpr ref ? ref.getIdentifier() : null;
        if (name == null || site.receiverTypes().isEmpty()) {
            return List.of();
        }
        TypeDeclaration<?> repo = site.receiverTypes().get(0);
        Optional<Base> base = base(repo, Map.of(), 0);
        MethodDeclaration declared = site.callees().isEmpty() ? null : site.callees().get(0);
        String op = base.isEmpty() || declared != null && declared.getBody().isPresent() ? null : op(name, declared);
        if (op == null) {
            return List.of(); // not a repository, a default method (walked on), or Object's methods
        }
        return List.of(OutboundCall.db(base.get().tech(), table(base.get().entity()), op, repo.getNameAsString() + "." + name));
    }

    /** The Spring interface reached from {@code t} and its entity argument, type variables bound along the way. */
    private Optional<Base> base(TypeDeclaration<?> t, Map<String, Type> bound, int depth) {
        if (!(t instanceof ClassOrInterfaceDeclaration c) || !c.isInterface() || depth > 10) {
            return Optional.empty();
        }
        for (ClassOrInterfaceType ext : c.getExtendedTypes()) {
            List<Type> args = ext.getTypeArguments().map(a -> a.stream().map(x -> bound.getOrDefault(x.asString(), x)).toList())
                    .orElse(List.of());
            Optional<TypeDeclaration<?>> own = types.resolveExact(ext);
            if (own.isPresent() && own.get() instanceof ClassOrInterfaceDeclaration sup) {
                Map<String, Type> next = new HashMap<>();
                for (int i = 0; i < Math.min(args.size(), sup.getTypeParameters().size()); i++) {
                    next.put(sup.getTypeParameters().get(i).getNameAsString(), args.get(i));
                }
                Optional<Base> b = base(sup, next, depth + 1);
                if (b.isPresent()) {
                    return b;
                }
            } else if (SPRING.containsKey(ext.getNameAsString()) && !args.isEmpty() && args.get(0) instanceof ClassOrInterfaceType e) {
                return Optional.of(new Base(SPRING.get(ext.getNameAsString()), e));
            }
        }
        return Optional.empty();
    }

    private String table(ClassOrInterfaceType entityRef) {
        Optional<TypeDeclaration<?>> entity = types.resolve(entityRef);
        if (entity.isEmpty()) {
            return snake(entityRef.getNameAsString());
        }
        return attr(entity.get(), "Table", "name", "value").or(() -> attr(entity.get(), "Entity", "name"))
                .orElseGet(() -> snake(entity.get().getNameAsString()));
    }

    /** A non-blank String attribute of the type's annotation {@code simpleName}. */
    private Optional<String> attr(TypeDeclaration<?> t, String simpleName, String... names) {
        Optional<AnnotationExpr> a = t.getAnnotations().stream().filter(x -> x.getName().getIdentifier().equals(simpleName)).findFirst();
        if (a.isEmpty()) {
            return Optional.empty();
        }
        List<String> wanted = List.of(names);
        return (a.get() instanceof SingleMemberAnnotationExpr s && wanted.contains("value") ? Optional.of(s.getMemberValue())
                : a.get() instanceof NormalAnnotationExpr n ? n.getPairs().stream().filter(p -> wanted.contains(p.getNameAsString()))
                        .map(MemberValuePair::getValue).findFirst() : Optional.<com.github.javaparser.ast.expr.Expression>empty())
                .flatMap(e -> constants.eval(e, t)).filter(v -> !v.isBlank());
    }

    /**
     * By name prefix (findBy..., saveAll, deleteById). A repository method with another name: write when
     * {@code @Modifying}, else read (a {@code @Query} SELECT); a name Spring doesn't declare (equals) is no call.
     */
    // shortcut: @Query/@Modifying methods get their op from the name and the entity's table, not from the SQL;
    // #11 parses the query (DELETE FROM other_table) and adds EntityManager/DatabaseClient
    static String op(String name, MethodDeclaration declared) {
        for (Map.Entry<String, List<String>> e : OPS.entrySet()) {
            for (String p : e.getValue()) {
                if (name.startsWith(p) && (name.length() == p.length() || !Character.isLowerCase(name.charAt(p.length())))) {
                    return e.getKey();
                }
            }
        }
        if (declared == null) {
            return null;
        }
        return declared.getAnnotationByName("Modifying").isPresent() ? "write" : "read";
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
