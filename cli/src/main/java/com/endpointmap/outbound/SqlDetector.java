package com.endpointmap.outbound;

import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.FlowTracer;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.endpointmap.url.UrlEvaluator;
import com.endpointmap.url.UrlEvaluator.Env;
import com.endpointmap.workspace.Names;
import com.endpointmap.workspace.Types;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Database calls outside repositories: {@code EntityManager} (JPQL {@code createQuery}, native
 * {@code createNativeQuery}, {@code persist/merge/remove/find} by the entity's table), {@code R2dbcEntityTemplate}
 * (entity from the {@code X.class} argument or the entity passed), {@code DatabaseClient.sql(..)} and Spring JDBC
 * ({@code JdbcTemplate}, {@code NamedParameterJdbcTemplate}, {@code JdbcClient.sql(..)}). The SQL is evaluated like a
 * URL (constants, concatenation, the caller's arguments) and read by {@link Sql}; what code does not tell becomes an
 * unresolved db target, never a guessed table.
 */
public final class SqlDetector implements OutboundDetector {

    private static final Map<String, String> EM_OPS = Map.of("persist", "write", "merge", "write", "remove", "delete",
            "find", "read", "getReference", "read", "refresh", "read");
    private static final Map<String, String> TEMPLATE_OPS = Map.of("select", "read", "selectOne", "read", "count", "read",
            "exists", "read", "insert", "write", "update", "write", "delete", "delete");

    private final RepositoryDetector repos;
    private final UrlEvaluator urls;
    private final Types types;

    public SqlDetector(RepositoryDetector repos, UrlEvaluator urls, Types types) {
        this.repos = repos;
        this.urls = urls;
        this.types = types;
    }

    @Override
    public List<OutboundCall> detect(CallSite site) {
        ClassOrInterfaceType t = site.receiverType();
        if (!(site.call() instanceof MethodCallExpr mc) || t == null || !site.receiverTypes().isEmpty() || mc.getArguments().isEmpty()) {
            return List.of(); // a workspace type of the same name is not the framework's
        }
        String type = t.getNameAsString();
        String name = mc.getNameAsString();
        String label = type + "." + name;
        Expression first = mc.getArgument(0);
        if (type.equals("EntityManager") && (Types.fromPackage(t, "jakarta.persistence.") || Types.fromPackage(t, "javax.persistence."))) {
            return switch (name) {
                case "createQuery" -> sql(first, true, "jpa", label, site);
                case "createNativeQuery" -> sql(first, false, "jpa", label, site);
                // shortcut: a named or stored-procedure query is not looked up; resolve @NamedQuery when real code uses it
                case "createNamedQuery", "createStoredProcedureQuery" -> List.of(new OutboundCall("jpa", null, null, null, null,
                        "query " + first + " is not read", label, null, "unknown", false));
                default -> EM_OPS.containsKey(name) ? entity(first, "jpa", EM_OPS.get(name), label, site) : List.of();
            };
        }
        if ((type.equals("R2dbcEntityTemplate") || type.equals("R2dbcEntityOperations")) && TEMPLATE_OPS.containsKey(name)
                && Types.fromPackage(t, "org.springframework.data.r2dbc.")) {
            // shortcut: a fluent .from("table")/.into("table") after the entity class is not read; the entity's table counts
            Expression entity = mc.getArguments().stream().filter(ClassExpr.class::isInstance).findFirst().orElse(first);
            return entity(entity, "r2dbc", TEMPLATE_OPS.get(name), label, site);
        }
        if (type.equals("DatabaseClient") && name.equals("sql") && Types.fromPackage(t, "org.springframework.r2dbc.")) {
            return sql(first, false, "r2dbc", label, site);
        }
        if (Types.fromPackage(t, "org.springframework.jdbc.") && (type.equals("JdbcClient") && name.equals("sql")
                || (type.equals("JdbcTemplate") || type.equals("NamedParameterJdbcTemplate"))
                && name.matches("query.*|update|batchUpdate|execute"))) {
            return sql(first, false, "jdbc", label, site);
        }
        return List.of();
    }

    private List<OutboundCall> sql(Expression e, boolean jpql, String tech, String label, CallSite site) {
        List<String> texts = urls.each(Env.of(site), env -> urls.string(e, env).text()).stream().distinct().toList();
        return repos.sqlCalls(texts, e.toString(), jpql, tech, label, site.call(), "unknown");
    }

    /** The table of the entity passed ({@code item}, {@code new Audit(..)}) or named ({@code Review.class}). */
    private List<OutboundCall> entity(Expression e, String tech, String op, String label, CallSite site) {
        Type type = e instanceof ClassExpr c ? c.getType() : e instanceof ObjectCreationExpr o ? o.getType()
                : e instanceof NameExpr n ? declared(n).orElse(null) : null;
        // an Object or a type outside the workspace names no entity
        List<String> tables = type instanceof ClassOrInterfaceType ct && (FlowTracer.typeVariableOwner(ct) != null || types.resolve(ct).isPresent())
                ? repos.tables(ct, site) : List.of();
        if (tables.isEmpty()) {
            return List.of(new OutboundCall(tech, null, null, null, null, "entity type of " + e + " not known from code",
                    label, null, op, false));
        }
        boolean guess = tables.size() > 1;
        return tables.stream().sorted().map(t -> OutboundCall.db(tech, t, op, label, guess)).toList();
    }

    /** A variable's, parameter's or field's declared type (a lambda parameter without one: empty). */
    private static Optional<Type> declared(NameExpr n) {
        return Names.declaration(n.getNameAsString(), n)
                .map(d -> d instanceof Parameter p ? p.getType() : d instanceof VariableDeclarator v ? v.getType() : null);
    }
}
