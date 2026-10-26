package com.endpointmap.url;

import com.endpointmap.endpoints.ConstantEvaluator;
import com.endpointmap.trace.ArgValue;
import com.endpointmap.trace.ArgumentBinder;
import com.endpointmap.trace.CallSite;
import com.endpointmap.workspace.Types;
import com.endpointmap.workspace.Workspace;
import com.endpointmap.workspace.Workspace.Repo;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates URL expressions statically (PLAN.md "URL resolution"): literals, {@code static final} constants,
 * {@code @Value} fields and parameters, {@code @ConfigurationProperties} getters and record accessors, concatenation,
 * {@code String.format}, {@code UriComponentsBuilder}/{@code uri(b -> b.path(..))} chains and workspace helper methods
 * ({@code UrlUtils.join(base, PATH)}), with the caller's arguments bound to parameters. Placeholders stay as written
 * ({@code ${a:default}}) for {@link #toCall}; anything else not known from code becomes {@code {*}}.
 * Also finds the base URL of a WebClient/RestClient expression: {@code create(url)} / {@code builder().baseUrl(url)},
 * through fields, constructor assignments and injected {@code @Bean}s (by type, then {@code @Qualifier}/bean name).
 */
public final class UrlEvaluator implements ArgumentBinder {

    public static final String ANY = "{*}";
    private static final int MAX_DEPTH = 12;
    private static final Set<String> CLIENTS = Set.of("WebClient", "RestClient");
    /** Parameter types whose arguments are bound (clients, URLs, verbs); others are never URL parts. */
    private static final Set<String> BOUND = Set.of("String", "CharSequence", "Object", "URI", "HttpMethod",
            "WebClient", "RestClient");
    private static final Set<String> URI_BUILDERS = Set.of("UriComponentsBuilder", "ServletUriComponentsBuilder");
    private static final Set<String> URI_PASS = Set.of("queryParam", "queryParams", "queryParamIfPresent",
            "replaceQueryParam", "fragment", "encode", "build", "buildAndExpand", "expand", "toUriString", "toUri",
            "toString", "uriVariables", "normalize");
    private static final Pattern FORMAT = Pattern.compile("%(%|n|[-#+ 0,(<]*\\d*(?:\\.\\d+)?[a-zA-Z])");
    private static final Pattern ORIGIN = Pattern.compile("^([a-zA-Z][\\w+.-]*://[^/?#]*)(.*)$");

    /** Where an expression is evaluated: the walked method and its bound arguments, the calling service's config. */
    public record Env(MethodDeclaration method, Map<String, ArgValue> args, Repo service, Map<String, String> config) {

        public static Env of(CallSite site) {
            return new Env(site.method(), site.args(), site.service(), site.config());
        }

        /** Code outside the walk (field initializer, constructor, @Bean method): nothing bound. */
        Env outside() {
            return new Env(null, Map.of(), service, config);
        }
    }

    /** A {@code @Bean} method making a client: its name(s), qualifier and type. */
    private record Bean(MethodDeclaration method, Set<String> names, String qualifier, boolean primary, String type) {
    }

    private final Types types;
    private final ConstantEvaluator constants;
    private final List<Bean> beans = new ArrayList<>();

    public UrlEvaluator(Workspace ws, Types types, ConstantEvaluator constants) {
        this.types = types;
        this.constants = constants;
        ws.repos().stream().flatMap(r -> r.sources().stream()).flatMap(sf -> sf.cu().findAll(MethodDeclaration.class).stream())
                .filter(m -> Workspace.hasAnnotation(m, "Bean"))
                .forEach(m -> {
                    Set<String> names = new java.util.HashSet<>(annotationValues(m, "Bean", "value", "name"));
                    if (names.isEmpty()) {
                        names.add(m.getNameAsString());
                    }
                    List<String> q = annotationValues(m, "Qualifier", "value");
                    beans.add(new Bean(m, names, q.isEmpty() ? null : q.get(0), Workspace.hasAnnotation(m, "Primary"),
                            simpleName(m.getType())));
                });
    }

    // --- argument binding (called by the tracer for every call it follows) ---

    @Override
    public Map<String, ArgValue> bind(MethodDeclaration callee, MethodCallExpr call, CallSite caller) {
        return bind(callee, call.getArguments(), Env.of(caller), 0);
    }

    private Map<String, ArgValue> bind(CallableDeclaration<?> callee, List<Expression> args, Env env, int depth) {
        Map<String, ArgValue> out = new HashMap<>();
        List<Parameter> params = callee.getParameters();
        for (int i = 0; i < params.size() && i < args.size(); i++) {
            Parameter p = params.get(i);
            String type = simpleName(p.getType());
            if (p.isVarArgs() || !BOUND.contains(type)) {
                continue; // varargs fill template variables, they are never the URL itself
            }
            Expression a = args.get(i);
            ArgValue v = CLIENTS.contains(type) ? client(a, env, depth + 1)
                    : type.equals("HttpMethod") ? verbValue(a, env, depth + 1) : string(a, env, depth + 1);
            if (v.text() != null && !v.text().equals(ANY) && v.hops() < MAX_HOPS) {
                out.put(p.getNameAsString(), new ArgValue(v.text(), v.hops() + 1));
            }
        }
        return out;
    }

    // --- strings ---

    /** The URL template an expression builds; never null ({@code {*}} when unknown). */
    public ArgValue string(Expression e, Env env) {
        return string(e, env, 0);
    }

    private ArgValue string(Expression e, Env env, int depth) {
        if (depth > MAX_DEPTH) {
            return any();
        }
        int d = depth + 1;
        if (e instanceof StringLiteralExpr s) {
            return known(s.asString());
        }
        if (e instanceof TextBlockLiteralExpr t) {
            return known(t.asString());
        }
        if (e instanceof CharLiteralExpr c) {
            return known(c.asChar() + "");
        }
        if (e instanceof IntegerLiteralExpr || e instanceof LongLiteralExpr) {
            return known(e.toString().replaceAll("[lL]$", ""));
        }
        if (e instanceof EnclosedExpr en) {
            return string(en.getInner(), env, d);
        }
        if (e instanceof CastExpr c) {
            return string(c.getExpression(), env, d);
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            return concat(string(b.getLeft(), env, d), string(b.getRight(), env, d));
        }
        if (e instanceof ConditionalExpr c) {
            Optional<Boolean> test = condition(c.getCondition(), env, d);
            return test.isPresent() ? string(test.get() ? c.getThenExpr() : c.getElseExpr(), env, d)
                    : best(string(c.getThenExpr(), env, d), string(c.getElseExpr(), env, d));
        }
        if (e instanceof NameExpr || e instanceof FieldAccessExpr) {
            Optional<String> constant = constants.eval(e, e);
            if (constant.isPresent()) {
                return known(constant.get());
            }
            return declaration(e).map(decl -> variable(decl, env, d, false)).orElse(any());
        }
        if (e instanceof ObjectCreationExpr o) {
            String type = o.getType().getNameAsString();
            if (type.equals("URI") && o.getArguments().size() == 1) {
                return string(o.getArgument(0), env, d);
            }
            // shortcut: appends after the constructor (a loop, usually) are not followed; the tail stays unknown
            if ((type.equals("StringBuilder") || type.equals("StringBuffer")) && o.getArguments().size() == 1) {
                return concat(string(o.getArgument(0), env, d), any());
            }
            return any();
        }
        if (e instanceof MethodCallExpr mc) {
            return call(mc, env, d);
        }
        return any();
    }

    private ArgValue call(MethodCallExpr mc, Env env, int d) {
        String name = mc.getNameAsString();
        String scopeName = mc.getScope().filter(NameExpr.class::isInstance).map(s -> ((NameExpr) s).getNameAsString()).orElse("");
        List<Expression> args = mc.getArguments();
        if (name.equals("format") && scopeName.equals("String") && !args.isEmpty()) {
            return format(string(args.get(0), env, d), args.subList(1, args.size()), env, d);
        }
        if (name.equals("formatted") && mc.getScope().isPresent()) {
            return format(string(mc.getScope().get(), env, d), args, env, d);
        }
        if ((name.equals("create") && scopeName.equals("URI") || name.equals("valueOf") && scopeName.equals("String"))
                && args.size() == 1) {
            return string(args.get(0), env, d);
        }
        Optional<ArgValue> uri = uriChain(mc, env, d);
        if (uri.isPresent()) {
            return uri.get();
        }
        if (name.equals("concat") && mc.getScope().isPresent() && args.size() == 1) {
            return concat(string(mc.getScope().get(), env, d), string(args.get(0), env, d));
        }
        if ((name.equals("toString") || name.equals("trim") || name.equals("strip")) && args.isEmpty() && mc.getScope().isPresent()) {
            return string(mc.getScope().get(), env, d);
        }
        Optional<ArgValue> property = configProperty(mc, env);
        if (property.isPresent()) {
            return property.get();
        }
        return helper(mc, env, d, false).orElse(any());
    }

    /** {@code base.endsWith("/")} and the like, when the string is known once its placeholders are resolved. */
    private Optional<Boolean> condition(Expression c, Env env, int d) {
        if (c instanceof EnclosedExpr en) {
            return condition(en.getInner(), env, d);
        }
        if (c instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return condition(u.getExpression(), env, d).map(b -> !b);
        }
        if (!(c instanceof MethodCallExpr mc) || mc.getScope().isEmpty() || mc.getArguments().size() > 1) {
            return Optional.empty();
        }
        String v = com.endpointmap.config.ConfigResolver.resolve(string(mc.getScope().get(), env, d).text(), env.config());
        String a = mc.getArguments().isEmpty() ? "" : string(mc.getArgument(0), env, d).text();
        if (v.contains(ANY) || a.contains(ANY)) {
            return Optional.empty();
        }
        return switch (mc.getNameAsString() + "/" + mc.getArguments().size()) {
            case "endsWith/1" -> Optional.of(v.endsWith(a));
            case "startsWith/1" -> Optional.of(v.startsWith(a));
            case "contains/1" -> Optional.of(v.contains(a));
            case "equals/1" -> Optional.of(v.equals(a));
            case "isEmpty/0" -> Optional.of(v.isEmpty());
            case "isBlank/0" -> Optional.of(v.isBlank());
            default -> Optional.empty();
        };
    }

    /** {@code String.format}: each conversion takes the next argument. */
    private ArgValue format(ArgValue fmt, List<Expression> args, Env env, int d) {
        if (fmt.text() == null) {
            return any();
        }
        Matcher m = FORMAT.matcher(fmt.text());
        StringBuilder out = new StringBuilder();
        int next = 0;
        int hops = fmt.hops();
        while (m.find()) {
            String piece;
            if (m.group(1).equals("%")) {
                piece = "%";
            } else if (m.group(1).equals("n")) {
                piece = "\n";
            } else {
                ArgValue a = next < args.size() ? string(args.get(next++), env, d) : any();
                hops = Math.max(hops, a.hops());
                piece = a.text();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(piece));
        }
        m.appendTail(out);
        return new ArgValue(out.toString(), hops);
    }

    /**
     * {@code UriComponentsBuilder.fromUriString(base).path(..).pathSegment(..).queryParam(..).build()...} and the
     * builder lambda of {@code uri(b -> b.path(..).build())}; empty when the chain doesn't start at a URI builder.
     */
    private Optional<ArgValue> uriChain(MethodCallExpr outer, Env env, int d) {
        List<MethodCallExpr> chain = new ArrayList<>();
        Expression at = outer;
        while (at instanceof MethodCallExpr mc && mc.getScope().isPresent()) {
            chain.add(0, mc);
            at = mc.getScope().get();
            if (at instanceof NameExpr n && URI_BUILDERS.contains(n.getNameAsString())) {
                break;
            }
        }
        if (chain.isEmpty()) {
            return Optional.empty();
        }
        ArgValue v;
        MethodCallExpr root = chain.get(0);
        if (at instanceof NameExpr n && URI_BUILDERS.contains(n.getNameAsString())) {
            v = root.getNameAsString().equals("newInstance") || root.getArguments().isEmpty() ? known("")
                    : string(root.getArgument(0), env, d);
            chain.remove(0);
        } else if (at instanceof NameExpr n && declaration(n).filter(p -> p instanceof Parameter
                && p.getParentNode().filter(l -> l instanceof LambdaExpr && l.getParentNode()
                .filter(u -> u instanceof MethodCallExpr um && um.getNameAsString().equals("uri")).isPresent()).isPresent()).isPresent()) {
            v = known(""); // the UriBuilder handed to uri(Function): starts from the client's base URL
        } else {
            return Optional.empty();
        }
        for (MethodCallExpr mc : chain) {
            String name = mc.getNameAsString();
            if (name.equals("path") && mc.getArguments().size() == 1) {
                v = joinPath(v, string(mc.getArgument(0), env, d));
            } else if (name.equals("pathSegment")) {
                for (Expression s : mc.getArguments()) {
                    v = concat(concat(v, known("/")), string(s, env, d));
                }
            } else if (!URI_PASS.contains(name)) {
                return Optional.of(any()); // scheme/host/port set piecemeal: not followed
            }
        }
        return Optional.of(v);
    }

    /**
     * A getter of a {@code @ConfigurationProperties} class or a record accessor: {@code ${prefix.kebab-name}},
     * with the field's initializer as default.
     */
    private Optional<ArgValue> configProperty(MethodCallExpr mc, Env env) {
        if (!mc.getArguments().isEmpty() || mc.getScope().isEmpty()) {
            return Optional.empty();
        }
        Optional<TypeDeclaration<?>> type = declaration(mc.getScope().get()).flatMap(this::declaredType);
        if (type.isEmpty() || !Workspace.hasAnnotation(type.get(), "ConfigurationProperties")) {
            return Optional.empty();
        }
        List<String> prefix = annotationValues(type.get(), "ConfigurationProperties", "value", "prefix");
        String name = mc.getNameAsString();
        if (!(type.get() instanceof RecordDeclaration)) {
            Matcher g = Pattern.compile("^(?:get|is)([A-Z].*)$").matcher(name);
            if (!g.matches()) {
                return Optional.empty();
            }
            name = Character.toLowerCase(g.group(1).charAt(0)) + g.group(1).substring(1);
        }
        String p = prefix.isEmpty() ? "" : prefix.get(0) + ".";
        String kebab = p + name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
        String key = !env.config().containsKey(kebab) && env.config().containsKey(p + name) ? p + name : kebab;
        String field = name;
        Optional<String> def = type.get().getFieldByName(field).flatMap(f -> f.getVariable(0).getInitializer())
                .flatMap(i -> constants.eval(i, i));
        return Optional.of(known("${" + key + def.map(v -> ":" + v).orElse("") + "}"));
    }

    // --- clients ---

    /** "WebClient" or "RestClient" when the expression is one of those clients, by declared type or factory call. */
    public Optional<String> clientType(Expression e) {
        for (int i = 0; i < MAX_DEPTH; i++) {
            if (e instanceof MethodCallExpr mc) {
                if (mc.getScope().isEmpty()) {
                    String n = mc.getNameAsString();
                    return e.findAncestor(TypeDeclaration.class).stream()
                            .flatMap(t -> ((TypeDeclaration<?>) t).getMethodsByName(n).stream())
                            .map(m -> clientIn(m.getType().asString())).flatMap(Optional::stream).findFirst();
                }
                e = mc.getScope().get();
            } else if (e instanceof EnclosedExpr en) {
                e = en.getInner();
            } else if (e instanceof NameExpr n && CLIENTS.contains(n.getNameAsString()) && declaration(n).isEmpty()) {
                return Optional.of(n.getNameAsString()); // WebClient.create(..) / RestClient.builder()
            } else {
                return declaration(e).flatMap(UrlEvaluator::typeOfDecl).flatMap(t -> clientIn(t.asString()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> clientIn(String type) {
        // WebClient, org...WebClient, WebClient.Builder (a builder chain still ends in build())
        return CLIENTS.stream().filter(c -> type.equals(c) || type.endsWith("." + c) || type.startsWith(c + ".")).findFirst();
    }

    /** Base URL of a client expression: text "" when it has none, null when it can't be told. */
    public ArgValue client(Expression e, Env env) {
        return client(e, env, 0);
    }

    private ArgValue client(Expression e, Env env, int depth) {
        if (depth > MAX_DEPTH) {
            return unknown();
        }
        int d = depth + 1;
        if (e instanceof EnclosedExpr en) {
            return client(en.getInner(), env, d);
        }
        if (e instanceof CastExpr c) {
            return client(c.getExpression(), env, d);
        }
        if (e instanceof MethodCallExpr mc) {
            String name = mc.getNameAsString();
            Optional<Expression> scope = mc.getScope();
            boolean factory = scope.filter(s -> s instanceof NameExpr n && CLIENTS.contains(n.getNameAsString())
                    && declaration(n).isEmpty()).isPresent();
            if (name.equals("baseUrl") && mc.getArguments().size() == 1) {
                return string(mc.getArgument(0), env, d);
            }
            if (factory) {
                // create() / create(url) / builder(): a String argument is the base URL
                return name.equals("create") && mc.getArguments().size() == 1 ? string(mc.getArgument(0), env, d) : known("");
            }
            Optional<ArgValue> made = helper(mc, env, d, true);
            if (made.isPresent()) {
                return made.get();
            }
            // build(), mutate(), defaultHeader(..), filter(..): the base set further down the chain
            return scope.map(s -> client(s, env, d)).orElse(unknown());
        }
        if (e instanceof NameExpr || e instanceof FieldAccessExpr) {
            return declaration(e).map(decl -> variable(decl, env, d, true)).orElse(unknown());
        }
        return unknown();
    }

    /** The bean a client parameter/field is injected with: by type, then @Qualifier or bean name, then @Primary. */
    private ArgValue bean(Type type, Node annotated, String name, Env env, int d) {
        String t = simpleName(type);
        List<Bean> candidates = beans.stream().filter(b -> b.type().equals(t)).filter(b -> {
            Repo r = types.repo(b.method());
            return r == env.service() || r != null && r.kind() == Workspace.Kind.library;
        }).toList();
        List<String> q = annotated instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> na
                ? annotationValues(na, "Qualifier", "value") : List.of();
        List<Bean> hit;
        if (!q.isEmpty()) {
            hit = candidates.stream().filter(b -> q.get(0).equals(b.qualifier()) || b.names().contains(q.get(0))).toList();
        } else if (candidates.size() == 1) {
            hit = candidates;
        } else {
            hit = candidates.stream().filter(b -> b.names().contains(name)).toList();
            if (hit.isEmpty()) {
                hit = candidates.stream().filter(Bean::primary).toList();
            }
        }
        // shortcut: no bean, or several that the injection point can't tell apart: unknown (a callSites entry)
        return hit.size() == 1 ? returned(hit.get(0).method(), env.outside(), d, true) : unknown();
    }

    // --- shared: variables, fields, helper methods ---

    /** Value of a parameter, local or field, as a URL string or (asClient) a client base. */
    private ArgValue variable(Node decl, Env env, int d, boolean asClient) {
        if (decl instanceof Parameter p) {
            Optional<Node> owner = p.getParentNode();
            if (owner.isPresent() && owner.get() == env.method() && env.args().containsKey(p.getNameAsString())) {
                return env.args().get(p.getNameAsString());
            }
            Optional<String> value = valueAnnotation(p);
            if (value.isPresent()) {
                return known(value.get());
            }
            if (asClient && simpleName(p.getType()).equals("Builder")) {
                return known(""); // the WebClient.Builder Spring Boot injects has no base URL
            }
            // a constructor or @Bean method parameter is injected by Spring
            boolean injected = owner.filter(o -> o instanceof ConstructorDeclaration
                    || o instanceof MethodDeclaration m && Workspace.hasAnnotation(m, "Bean")).isPresent();
            return asClient && injected ? bean(p.getType(), p, p.getNameAsString(), env, d) : asClient ? unknown() : any();
        }
        VariableDeclarator v = (VariableDeclarator) decl;
        if (!(v.getParentNode().orElse(null) instanceof FieldDeclaration f)) {
            return v.getInitializer().map(i -> asClient ? client(i, env, d) : string(i, env, d))
                    .orElse(asClient ? unknown() : any());
        }
        Optional<String> value = valueAnnotation(f);
        if (value.isPresent()) {
            return known(value.get());
        }
        Env outside = env.outside();
        if (v.getInitializer().isPresent()) {
            return asClient ? client(v.getInitializer().get(), outside, d) : string(v.getInitializer().get(), outside, d);
        }
        // assigned in a constructor: this.client = WebClient.create(url) / this.url = url
        String name = v.getNameAsString();
        Optional<TypeDeclaration<?>> owner = f.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t);
        for (ConstructorDeclaration c : owner.map(TypeDeclaration::getConstructors).orElse(List.of())) {
            for (AssignExpr a : c.findAll(AssignExpr.class)) {
                Expression target = a.getTarget();
                boolean self = target instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr && fa.getNameAsString().equals(name)
                        || target instanceof NameExpr n && n.getNameAsString().equals(name);
                if (self && a.getOperator() == AssignExpr.Operator.ASSIGN) {
                    ArgValue r = asClient ? client(a.getValue(), outside, d) : string(a.getValue(), outside, d);
                    if (asClient ? r.text() != null : !r.text().equals(ANY)) {
                        return r;
                    }
                }
            }
        }
        // shortcut: only the first constructor assignment that tells anything is used; this(..) chains are not followed
        return asClient ? bean(f.getElementType(), f, name, env, d) : any();
    }

    /** A workspace method called with these arguments: the value it returns. Empty when it isn't one. */
    private Optional<ArgValue> helper(MethodCallExpr mc, Env env, int d, boolean asClient) {
        Optional<MethodDeclaration> m = resolve(mc);
        if (m.isEmpty() || m.get().getBody().isEmpty()) {
            return Optional.empty();
        }
        Map<String, ArgValue> args = bind(m.get(), mc.getArguments(), env, d);
        return Optional.of(returned(m.get(), new Env(m.get(), args, env.service(), env.config()), d, asClient));
    }

    /** The returned value of a method body; of several returns the most complete one. */
    private ArgValue returned(MethodDeclaration m, Env env, int d, boolean asClient) {
        ArgValue out = asClient ? unknown() : any();
        for (ReturnStmt r : m.findAll(ReturnStmt.class, r -> r.findAncestor(LambdaExpr.class)
                .map(l -> !m.isAncestorOf(l)).orElse(true))) {
            if (r.getExpression().isEmpty()) {
                continue;
            }
            ArgValue v = asClient ? client(r.getExpression().get(), env, d) : string(r.getExpression().get(), env, d);
            out = asClient ? (out.text() == null ? v : out) : best(out, v);
        }
        return out;
    }

    private Optional<MethodDeclaration> resolve(MethodCallExpr mc) {
        try {
            return mc.resolve().toAst(MethodDeclaration.class).map(types::canonical);
        } catch (RuntimeException | StackOverflowError e) {
            // the solver fails on anything touching Spring: by name, for "Util.join(..)" and same-class calls
            Optional<TypeDeclaration<?>> owner = mc.getScope().isEmpty()
                    ? mc.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t)
                    : mc.getScope().get() instanceof NameExpr n && declaration(n).isEmpty()
                    ? types.bySimpleNameNear(n.getNameAsString(), mc)
                    : declaration(mc.getScope().get()).flatMap(this::declaredType);
            List<MethodDeclaration> ms = owner.stream().flatMap(t -> t.getMethodsByName(mc.getNameAsString()).stream())
                    .filter(x -> x.getParameters().size() == mc.getArguments().size()).toList();
            return ms.size() == 1 ? Optional.of(ms.get(0)) : Optional.empty();
        }
    }

    /** {@code HttpMethod.POST}, a static import {@code POST}, a bound parameter, {@code HttpMethod.valueOf("POST")}. */
    public String verb(Expression e, Env env) {
        String v = verbValue(e, env, 0).text();
        return v == null ? "ANY" : v;
    }

    private ArgValue verbValue(Expression e, Env env, int d) {
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof NameExpr n && n.getNameAsString().equals("HttpMethod")) {
            return known(fa.getNameAsString());
        }
        if (e instanceof MethodCallExpr mc && mc.getNameAsString().equals("valueOf") && mc.getArguments().size() == 1) {
            String s = string(mc.getArgument(0), env, d + 1).text();
            return s.equals(ANY) ? unknown() : known(s.toUpperCase());
        }
        if (e instanceof NameExpr n) {
            Optional<Node> decl = declaration(n);
            if (decl.isEmpty()) {
                return n.getNameAsString().matches("[A-Z]+") ? known(n.getNameAsString()) : unknown();
            }
            if (decl.get() instanceof Parameter p && p.getParentNode().orElse(null) == env.method()) {
                return env.args().getOrDefault(p.getNameAsString(), unknown());
            }
            if (decl.get() instanceof VariableDeclarator v && v.getInitializer().isPresent() && d < MAX_DEPTH) {
                return verbValue(v.getInitializer().get(), env, d + 1);
            }
        }
        return unknown();
    }

    // --- results ---

    /**
     * The call a template URL gives: absolute when {@code uri} has a scheme or starts with a placeholder, else below
     * {@code base} (null = unknown client). Base unknown because of a placeholder nothing defines: key is that
     * placeholder (as {@code ${HOST}}) and the rest of the URL the path; unknown otherwise: no key (a callSites entry).
     */
    public static Resolved toCall(String base, String uri, Map<String, String> config) {
        String raw;
        if (uri.startsWith("${") || ORIGIN.matcher(uri).matches()) {
            raw = uri;
        } else if (uri.startsWith(ANY) || base == null) {
            return new Resolved(null, uri.startsWith(ANY) ? stripLeading(uri) : uri, null, uri);
        } else {
            raw = base.endsWith("/") && uri.startsWith("/") ? base + uri.substring(1)
                    : base.isEmpty() || base.endsWith("/") || uri.startsWith("/") || uri.isEmpty() ? base + uri : base + "/" + uri;
        }
        String url = com.endpointmap.config.ConfigResolver.resolve(raw, config);
        Matcher m = ORIGIN.matcher(url);
        if (m.matches() && !m.group(1).contains(ANY)) {
            return new Resolved(m.group(1), m.group(2), null, raw);
        }
        String path = m.matches() ? m.group(2) : url.startsWith(ANY) ? stripLeading(url) : url;
        return new Resolved(null, path, firstUndefined(raw, config), raw);
    }

    /** {@code baseUrl} null when unknown; {@code key} then the placeholder to ask about, null for a call site. */
    public record Resolved(String baseUrl, String path, String key, String raw) {
    }

    private static String stripLeading(String url) {
        String rest = url.substring(ANY.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? ANY : rest.substring(slash);
    }

    /**
     * The first {@code ${KEY}} with no default that the config doesn't define, followed through property values and
     * defaults ({@code ${a}} -> {@code a: ${ENV}} gives {@code ${ENV}}); null when every placeholder resolves.
     */
    public static String firstUndefined(String raw, Map<String, String> config) {
        return firstUndefined(raw, config, 0);
    }

    private static String firstUndefined(String raw, Map<String, String> config, int depth) {
        int from = 0;
        while (depth < 10) {
            int start = raw.indexOf("${", from);
            if (start < 0) {
                return null;
            }
            int nested = 0;
            int end = -1;
            int colon = -1;
            for (int i = start + 2; i < raw.length() && end < 0; i++) {
                char c = raw.charAt(i);
                if (c == '{') {
                    nested++;
                } else if (c == '}' && nested-- == 0) {
                    end = i;
                } else if (c == ':' && nested == 0 && colon < 0) {
                    colon = i;
                }
            }
            if (end < 0) {
                return null;
            }
            String key = raw.substring(start + 2, colon < 0 ? end : colon);
            String inner = config.containsKey(key) ? config.get(key) : colon < 0 ? null : raw.substring(colon + 1, end);
            if (inner == null) {
                return "${" + key + "}";
            }
            String found = firstUndefined(inner, config, depth + 1);
            if (found != null) {
                return found;
            }
            from = end + 1;
        }
        return null;
    }

    // --- helpers ---

    private Optional<TypeDeclaration<?>> declaredType(Node decl) {
        return typeOfDecl(decl).filter(ClassOrInterfaceType.class::isInstance).flatMap(t -> types.resolve((ClassOrInterfaceType) t));
    }

    private static Optional<Type> typeOfDecl(Node decl) {
        return decl instanceof Parameter p ? Optional.of(p.getType())
                : decl instanceof VariableDeclarator v ? Optional.of(v.getType()) : Optional.empty();
    }

    /** The parameter, local variable or field a name (or {@code this.name}) refers to, innermost first. */
    private Optional<Node> declaration(Expression e) {
        String name;
        if (e instanceof NameExpr ne) {
            name = ne.getNameAsString();
        } else if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
            return field(fa.getNameAsString(), e);
        } else {
            return Optional.empty();
        }
        for (Node n = e; n != null; n = n.getParentNode().orElse(null)) {
            List<Parameter> params = n instanceof CallableDeclaration<?> cd ? cd.getParameters()
                    : n instanceof LambdaExpr l ? l.getParameters() : List.of();
            for (Parameter p : params) {
                if (p.getNameAsString().equals(name)) {
                    return Optional.of(p);
                }
            }
            if (n instanceof CallableDeclaration<?> || n instanceof LambdaExpr) {
                Optional<VariableDeclarator> local = n.findFirst(VariableDeclarator.class, v -> v.getNameAsString().equals(name)
                        && !(v.getParentNode().orElse(null) instanceof FieldDeclaration));
                if (local.isPresent()) {
                    return Optional.of(local.get());
                }
            }
            if (n instanceof TypeDeclaration<?> td) {
                Optional<Node> f = td.getFieldByName(name).map(fd -> fd.getVariables().stream()
                        .filter(v -> v.getNameAsString().equals(name)).findFirst().orElseThrow());
                if (f.isPresent()) {
                    return f;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Node> field(String name, Node at) {
        return at.findAncestor(TypeDeclaration.class).flatMap(t -> ((TypeDeclaration<?>) t).getFieldByName(name))
                .flatMap(f -> f.getVariables().stream().filter(v -> v.getNameAsString().equals(name)).findFirst());
    }

    /** {@code @Value("${x:default}")} as written. */
    private Optional<String> valueAnnotation(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> n) {
        return n.getAnnotationByName("Value").flatMap(a -> memberValue(a, "value")).flatMap(v -> constants.eval(v, v));
    }

    private List<String> annotationValues(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> n, String annotation,
                                          String... members) {
        List<String> out = new ArrayList<>();
        Optional<AnnotationExpr> a = n.getAnnotationByName(annotation);
        for (String member : members) {
            Optional<Expression> v = a.flatMap(x -> memberValue(x, member));
            if (v.isPresent()) {
                List<Expression> items = v.get().isArrayInitializerExpr() ? v.get().asArrayInitializerExpr().getValues() : List.of(v.get());
                items.forEach(i -> constants.eval(i, i).ifPresent(out::add));
            }
        }
        return out;
    }

    private static Optional<Expression> memberValue(AnnotationExpr a, String name) {
        if (a instanceof SingleMemberAnnotationExpr s) {
            return name.equals("value") ? Optional.of(s.getMemberValue()) : Optional.empty();
        }
        if (a instanceof NormalAnnotationExpr n) {
            return n.getPairs().stream().filter(p -> p.getNameAsString().equals(name)).map(MemberValuePair::getValue).findFirst();
        }
        return Optional.empty();
    }

    private static String simpleName(Type t) {
        String s = t.asString().replaceAll("<.*>", "");
        return s.substring(s.lastIndexOf('.') + 1);
    }

    private static ArgValue concat(ArgValue a, ArgValue b) {
        return new ArgValue(a.text() + b.text(), Math.max(a.hops(), b.hops()));
    }

    private static ArgValue joinPath(ArgValue a, ArgValue b) {
        String l = a.text();
        String r = b.text();
        String joined = l.endsWith("/") && r.startsWith("/") ? l + r.substring(1)
                : l.isEmpty() || l.endsWith("/") || r.startsWith("/") ? l + r : l + "/" + r;
        return new ArgValue(joined, Math.max(a.hops(), b.hops()));
    }

    /** Of two possible values (ternary branches, several returns), the one with fewer unknown parts; ties: the first. */
    private static ArgValue best(ArgValue a, ArgValue b) {
        return unknowns(b.text()) < unknowns(a.text()) ? b : a;
    }

    private static int unknowns(String s) {
        return s.equals(ANY) ? Integer.MAX_VALUE : s.split(Pattern.quote(ANY), -1).length - 1;
    }

    private static ArgValue known(String s) {
        return new ArgValue(s, 0);
    }

    private static ArgValue any() {
        return new ArgValue(ANY, 0);
    }

    private static ArgValue unknown() {
        return new ArgValue(null, 0);
    }
}
