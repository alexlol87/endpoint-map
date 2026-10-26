package com.endpointmap.outbound;

import com.endpointmap.endpoints.ConstantEvaluator;
import com.endpointmap.trace.ArgValue;
import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.FlowTracer;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A call on a Spring Data repository: an interface extending one of Spring's repository interfaces, directly or
 * through workspace interfaces (a library's base repository). Methods only Spring declares (save, findById...) are
 * matched by name on the receiver's type. Table from the entity (the first generic argument): {@code @Table(name)}
 * (JPA or spring.data.relational), else {@code @Entity(name)}, else the class name in snake_case, as Spring Boot's
 * default naming (Hibernate's CamelCaseToUnderscoresNamingStrategy, R2DBC's DefaultNamingStrategy) maps it.
 * The entity argument is bound from the receiver's declared type ({@code Base<Pet, Long>}) and, when it is still a
 * type variable, from what the path into it bound it to ({@code orderSvc.all()}, OrderSvc extends CrudSvc&lt;Order&gt;),
 * else from the calling service's subclasses (one edge each, low confidence when several); with none, an unresolved db target.
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

    /** Methods every object has: never a query, though getClass looks like one. */
    private static final Set<String> OBJECT = Set.of("getClass", "hashCode", "equals", "toString", "notify", "notifyAll", "wait");
    /** Entities of other Spring Data stores (Redis, Mongo, Neo4j) on the same CrudRepository interfaces: no table. */
    private static final Set<String> NOT_TABLES = Set.of("RedisHash", "Document", "Node");

    private final Workspace ws;
    private final Types types;
    private final ConstantEvaluator constants;
    /** Each workspace type's direct subtypes, as their extends/implements clause (lazy: only generic bases need it). */
    private Map<TypeDeclaration<?>, List<ClassOrInterfaceType>> subClauses;
    /** Workspace {@code @Entity} types by entity name (lazy: only JPQL needs it). */
    private Map<String, List<TypeDeclaration<?>>> entities;

    public RepositoryDetector(Workspace ws, Types types, ConstantEvaluator constants) {
        this.ws = ws;
        this.types = types;
        this.constants = constants;
    }

    @Override
    public List<OutboundCall> detect(CallSite site) {
        String name = site.call() instanceof MethodCallExpr mc ? mc.getNameAsString()
                : site.call() instanceof MethodReferenceExpr ref ? ref.getIdentifier() : null;
        if (name == null || OBJECT.contains(name)) {
            return List.of();
        }
        ClassOrInterfaceType written = site.receiverType();
        List<Type> writtenArgs = written == null ? List.of() : written.getTypeArguments().map(List::<Type>copyOf).orElse(List.of());
        String repoName;
        Optional<Base> base;
        if (!site.receiverTypes().isEmpty()) {
            TypeDeclaration<?> repo = site.receiverTypes().get(0);
            repoName = repo.getNameAsString();
            base = base(repo, bind(repo, writtenArgs), 0); // the receiver's own type arguments: Base<Pet, Long> base
        } else if (written != null && SPRING.containsKey(written.getNameAsString())
                && Types.fromPackage(written, FlowTracer.SPRING_DATA)) { // a field typed JpaRepository<E, Long>
            repoName = written.getNameAsString();
            base = spring(written, writtenArgs);
        } else {
            return List.of();
        }
        MethodDeclaration declared = site.callees().isEmpty() ? null : site.callees().get(0);
        String op = base.isEmpty() ? null : op(name, declared, base.get().entity());
        if (op == null) {
            return List.of(); // not a repository, a default method or custom implementation (walked on), or Object's methods
        }
        String label = repoName + "." + name;
        String tech = base.get().tech();
        ClassOrInterfaceType entity = base.get().entity();
        Optional<AnnotationExpr> query = declared == null ? Optional.empty() : declared.getAnnotationByName("Query");
        if (query.isPresent()) { // R2DBC's @Query is always SQL; JPA's is JPQL unless nativeQuery = true
            boolean jpql = tech.equals("jpa") && !(query.get() instanceof NormalAnnotationExpr n && n.getPairs().stream()
                    .anyMatch(p -> p.getNameAsString().equals("nativeQuery") && p.getValue().toString().equals("true")));
            String sql = attr(declared, "Query", "value").orElse(null);
            return sqlCalls(sql == null ? null : List.of(sql), query.get().toString(), jpql, tech, label, declared, op);
        }
        List<String> tables = tables(entity, site);
        if (tables.isEmpty() && FlowTracer.typeVariableOwner(entity) != null) {
            return List.of(new OutboundCall(tech, null, null, null, null, "the entity of " + repoName + " is the type variable "
                    + entity + ", bound to no entity class in " + site.service().name(), label, null, op, false));
        }
        boolean guess = tables.size() > 1; // any one of several subclasses' entities
        return tables.stream().sorted().map(t -> OutboundCall.db(tech, t, op, label, guess)).toList();
    }

    /**
     * The tables an entity type may stand for at this call: its own, or for a type variable the one the path into the
     * code bound ({@code orderSvc.all()}, OrderSvc extends CrudSvc&lt;Order&gt;), else every subclass binding in the
     * calling service. Empty: another store's entity, or a type variable nothing binds.
     */
    List<String> tables(ClassOrInterfaceType entity, CallSite site) {
        Node owner = FlowTracer.typeVariableOwner(entity);
        String key = owner == null ? null : FlowTracer.typeArgKey(owner, entity.getNameAsString()); // null: a method's own <T>
        ArgValue bound = key == null ? null : site.args().get(key);
        if (owner == null) {
            return table(entity).stream().toList();
        } else if (bound != null) { // bound on the path into this code
            return types.byQualifiedName(bound.text(), site.call()).map(this::table)
                    .orElseGet(() -> Optional.of(snake(bound.text().substring(bound.text().lastIndexOf('.') + 1)))).stream().toList();
        }
        List<ClassOrInterfaceType> entities = owner instanceof ClassOrInterfaceDeclaration c
                ? bindings(c, entity.getNameAsString(), site.service(), 0) : List.of();
        return entities.stream().map(this::table).flatMap(Optional::stream).distinct().toList();
    }

    /**
     * Db calls of a statement: one per table it touches, op from its verb ({@link Sql}). {@code texts} are the values
     * the SQL may have, {@code {*}} where code does not tell; null when it is no string at all. A statement not
     * understood, or a table name not known from code, gives an unresolved db target (op {@code fallbackOp} when the
     * verb is unknown) rather than a guess. JPQL names entities: each is mapped to its table.
     */
    List<OutboundCall> sqlCalls(List<String> texts, String shown, boolean jpql, String tech, String label, Node context, String fallbackOp) {
        List<OutboundCall> out = new ArrayList<>();
        for (String sql : texts == null ? Collections.<String>singletonList(null) : texts) {
            List<Sql.Access> accesses = sql == null ? null : Sql.parse(sql, jpql);
            if (accesses == null || accesses.isEmpty()) {
                String why = sql == null || sql.contains("{*}") ? "SQL not known from code: "
                        : accesses == null ? "SQL not understood: " : "no table in SQL: ";
                out.add(new OutboundCall(tech, null, null, null, null, why + (sql == null ? shown : sql.replaceAll("\\s+", " ").strip()),
                        label, null, fallbackOp, false));
                continue;
            }
            for (Sql.Access a : accesses) {
                if (a.table() == null) {
                    out.add(new OutboundCall(tech, null, null, null, null, "table name not known from code: "
                            + sql.replaceAll("\\s+", " ").strip(), label, null, a.op(), false));
                } else if (!jpql) {
                    out.add(OutboundCall.db(tech, a.table(), a.op(), label, false));
                } else {
                    Optional<TypeDeclaration<?>> e = entity(a.table(), context);
                    // shortcut: an entity name no workspace @Entity has is taken as its snake_case table, low confidence
                    out.add(e.isPresent() ? e.flatMap(this::table).map(t -> OutboundCall.db(tech, t, a.op(), label, false)).orElse(null)
                            : OutboundCall.db(tech, snake(a.table()), a.op(), label, true));
                }
            }
        }
        return out.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    /** The JPA entity a JPQL statement names: {@code @Entity(name)}, else the class name; the one in context's repo first. */
    private Optional<TypeDeclaration<?>> entity(String name, Node context) {
        if (entities == null) {
            entities = new HashMap<>();
            ws.repos().stream().flatMap(r -> r.sources().stream()).flatMap(sf -> sf.cu().findAll(TypeDeclaration.class).stream())
                    .filter(t -> t.getAnnotationByName("Entity").isPresent())
                    .forEach(t -> entities.computeIfAbsent(attr(t, "Entity", "name").map(RepositoryDetector::unquote)
                            .orElse(t.getNameAsString()), k -> new ArrayList<>()).add(t));
        }
        List<TypeDeclaration<?>> found = entities.getOrDefault(name, List.of());
        Repo repo = types.repo(context);
        return found.stream().filter(t -> types.repo(t) == repo).findFirst().or(() -> found.stream().findFirst());
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
            Optional<Base> b = own.isPresent() ? base(own.get(), bind(own.get(), args), depth + 1) : spring(ext, args);
            if (b.isPresent()) {
                return b;
            }
        }
        return Optional.empty();
    }

    private static Optional<Base> spring(ClassOrInterfaceType ref, List<Type> args) {
        return SPRING.containsKey(ref.getNameAsString()) && Types.fromPackage(ref, FlowTracer.SPRING_DATA) && !args.isEmpty() && args.get(0) instanceof ClassOrInterfaceType e
                ? Optional.of(new Base(SPRING.get(ref.getNameAsString()), e)) : Optional.empty();
    }

    /** {@code t}'s type parameters bound to {@code args}, by position. */
    private static Map<String, Type> bind(TypeDeclaration<?> t, List<Type> args) {
        Map<String, Type> next = new HashMap<>();
        if (t instanceof ClassOrInterfaceDeclaration c) {
            for (int i = 0; i < Math.min(args.size(), c.getTypeParameters().size()); i++) {
                next.put(c.getTypeParameters().get(i).getNameAsString(), args.get(i));
            }
        }
        return next;
    }

    /**
     * For a type variable ({@code E} of an {@code AbstractCrudService<E>}) every type the calling service's (or a
     * library's) subclasses bind it to: the code may run for any of them. Empty: none known.
     */
    private List<ClassOrInterfaceType> bindings(ClassOrInterfaceDeclaration owner, String var, Repo service, int depth) {
        int at = owner.getTypeParameters().stream().map(p -> p.getNameAsString()).toList().indexOf(var);
        List<ClassOrInterfaceType> out = new ArrayList<>();
        for (ClassOrInterfaceType clause : subClauses().getOrDefault(owner, List.of())) {
            Node sub = clause.getParentNode().orElse(null);
            Repo repo = types.repo(clause);
            if (repo != service && repo.kind() != Workspace.Kind.library
                    || !(clause.getTypeArguments().orElse(new NodeList<>()).stream().skip(at).findFirst().orElse(null) instanceof ClassOrInterfaceType a)) {
                continue;
            }
            Node aOwner = FlowTracer.typeVariableOwner(a);
            if (aOwner == null) {
                out.add(a);
            } else if (aOwner == sub && depth < 10) { // Mid<X> extends CrudSvc<X>: X as Mid's subclasses bind it
                out.addAll(bindings((ClassOrInterfaceDeclaration) sub, a.getNameAsString(), service, depth + 1));
            }
        }
        return out;
    }

    private Map<TypeDeclaration<?>, List<ClassOrInterfaceType>> subClauses() {
        if (subClauses == null) {
            subClauses = new IdentityHashMap<>();
            ws.repos().stream().flatMap(r -> r.sources().stream())
                    .flatMap(sf -> sf.cu().findAll(ClassOrInterfaceDeclaration.class).stream())
                    .flatMap(c -> Stream.concat(c.getExtendedTypes().stream(), c.getImplementedTypes().stream()))
                    .forEach(st -> types.resolve(st).ifPresent(s -> subClauses.computeIfAbsent(s, k -> new ArrayList<>()).add(st)));
        }
        return subClauses;
    }

    /**
     * Explicit names as written, quotes or backticks dropped; {@code schema.table} when {@code @Table(schema)} is set.
     * Empty for another store's entity ({@code @RedisHash}, {@code @Document}).
     */
    Optional<String> table(ClassOrInterfaceType entityRef) {
        return types.resolve(entityRef).map(this::table).orElseGet(() -> Optional.of(snake(entityRef.getNameAsString())));
    }

    Optional<String> table(TypeDeclaration<?> e) {
        if (e.getAnnotations().stream().anyMatch(a -> NOT_TABLES.contains(a.getName().getIdentifier()))) {
            return Optional.empty();
        }
        String name = attr(e, "Table", "name", "value").or(() -> attr(e, "Entity", "name")).map(RepositoryDetector::unquote)
                .orElseGet(() -> snake(e.getNameAsString()));
        return Optional.of(attr(e, "Table", "schema").map(sch -> unquote(sch) + "." + name).orElse(name));
    }

    private static String unquote(String name) {
        return name.replaceAll("^[\"`]+|[\"`]+$", "");
    }

    /** A non-blank String attribute of the type's or method's annotation {@code simpleName}. */
    private Optional<String> attr(BodyDeclaration<?> t, String simpleName, String... names) {
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
     * By name prefix (findBy..., saveAll, deleteById) for a derived query or a method only Spring declares; a
     * {@code @Query} method with another name: write when {@code @Modifying}, else read. A method of a fragment
     * interface (not a repository itself), or a repository method neither derived nor annotated, is a custom
     * implementation: no call, so its implementation is walked. Null too for a default method. {@code @Procedure}, or
     * a named query on the entity ({@code @NamedQuery(name = "Order.archived")}), counts as {@code @Query}.
     */
    private String op(String name, MethodDeclaration declared, ClassOrInterfaceType entity) {
        String byName = null;
        for (Map.Entry<String, List<String>> e : OPS.entrySet()) {
            for (String p : e.getValue()) {
                if (byName == null && name.startsWith(p) && (name.length() == p.length() || !Character.isLowerCase(name.charAt(p.length())))) {
                    byName = e.getKey();
                }
            }
        }
        if (declared == null) {
            return byName;
        }
        if (declared.getBody().isPresent()) {
            return null;
        }
        boolean modifying = declared.getAnnotationByName("Modifying").isPresent();
        if (!modifying && declared.getAnnotationByName("Query").isEmpty() && declared.getAnnotationByName("Procedure").isEmpty()
                && !namedQuery(entity, name)) {
            boolean fragment = declared.getParentNode().filter(TypeDeclaration.class::isInstance)
                    .map(t -> base((TypeDeclaration<?>) t, Map.of(), 0).isEmpty()).orElse(true);
            return fragment ? null : byName;
        }
        return byName != null ? byName : modifying ? "write" : "read";
    }

    /** The entity declares a JPA named query for this repository method: {@code @NamedQuery(name = "Entity.method")}. */
    private boolean namedQuery(ClassOrInterfaceType entity, String method) {
        return FlowTracer.typeVariableOwner(entity) == null && types.resolve(entity).stream()
                .flatMap(e -> e.getAnnotations().stream()).filter(a -> a.getName().getIdentifier().startsWith("Named"))
                .anyMatch(a -> a.toString().contains("." + method + "\""));
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
