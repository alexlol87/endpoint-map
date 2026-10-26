package com.endpointmap.trace;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.expr.SwitchExpr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds graph.json's {@code flows} (PLAN.md section 4): per endpoint, the merged call paths of every edge it has,
 * with the if/else, switch/case, loop and try/catch around each call. Only paths ending in an edge are kept, so a
 * util call leading to no edge never shows up. Children are in source order.
 */
public final class FlowTree {

    /** {@code kids} keyed by source position + type + label, so paths merge and siblings sort in source order. */
    private record Step(Map<String, Object> json, TreeMap<String, Step> kids) {
    }

    private record Leaf(Map<String, Object> edge, String pos, List<String> keyPath, List<Map<String, Object>> jsonPath, boolean cut) {
    }

    private final Function<String, Node> sites;
    private final Function<MethodCallExpr, List<String>> returnTypes;
    private final Map<String, List<Leaf>> leaves = new TreeMap<>();
    private final Set<String> truncated = new TreeSet<>();

    /** One line per flow branch {@link #build} cut short (shown there as a {@code truncated} leaf). */
    public List<String> truncated() {
        return List.copyOf(truncated);
    }

    /** {@code sites}: the call expression at a {@link FlowTracer.Found#sites()} entry. */
    /** {@code returnTypes}: declared return types, as written, of the workspace methods a call may reach. */
    public FlowTree(Function<String, Node> sites, Function<MethodCallExpr, List<String>> returnTypes) {
        this.sites = sites;
        this.returnTypes = returnTypes;
    }

    /**
     * Records every path of {@code f} (traced from {@code handler}) to the graph edge {@code edge} it produced: an edge
     * reached from several places (an if and its else, two callers of one util) is a leaf under each of them.
     */
    public void add(String endpoint, Node handler, FlowTracer.Found f, Map<String, Object> edge) {
        f.paths().forEach(p -> add(endpoint, handler, p, edge));
    }

    private void add(String endpoint, Node handler, FlowTracer.CallPath f, Map<String, Object> edge) {
        List<String> keys = new ArrayList<>();
        List<Map<String, Object>> json = new ArrayList<>();
        String last = "";
        int offset = f.via().size() - f.sites().size(); // an interface route handler: its implementation comes first
        for (int i = 0; i < offset; i++) {
            keys.add("0:" + f.via().get(i));
            json.add(node("call", f.via().get(i)));
        }
        int calls = f.cut() ? f.sites().size() : f.sites().size() - 1; // a cut marker ends inside its last callee
        for (int i = 0; i < f.sites().size(); i++) {
            Node call = sites.apply(f.sites().get(i));
            if (call == null) {
                throw new IllegalStateException("no call recorded at " + f.sites().get(i));
            }
            controls(call, handler, keys, json);
            last = pos(call);
            if (i < calls) {
                keys.add(pos(call) + ":call:" + f.via().get(i + offset));
                json.add(node("call", f.via().get(i + offset)));
            }
        }
        leaves.computeIfAbsent(endpoint, k -> new ArrayList<>()).add(new Leaf(edge, f.cut() ? "999999:999999" : last, keys, json, f.cut()));
    }

    /** The flows map; {@code edges} as written, so leaves hold their index. */
    public Map<String, Object> build(List<Map<String, Object>> edges) {
        Map<Map<String, Object>, Integer> index = new HashMap<>();
        Map<String, List<String>> next = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            index.putIfAbsent(edges.get(i), i);
            next.computeIfAbsent((String) edges.get(i).get("from"), k -> new ArrayList<>()).add((String) edges.get(i).get("to"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        leaves.forEach((endpoint, ls) -> {
            Step root = new Step(node("seq", null), new TreeMap<>());
            for (Leaf l : ls) {
                Integer idx = index.get(l.edge());
                if (idx == null) {
                    throw new IllegalStateException("flow leaf for an edge not in graph.json: " + l.edge());
                }
                Step at = root;
                for (int i = 0; i < l.keyPath().size(); i++) {
                    Map<String, Object> j = l.jsonPath().get(i);
                    at = at.kids().computeIfAbsent(l.keyPath().get(i), k -> new Step(j, new TreeMap<>()));
                }
                String to = (String) l.edge().get("to");
                if (l.cut()) { // after the shown paths: more of them reach this edge from here
                    Map<String, Object> mark = node("truncated", "more paths to this edge not shown");
                    mark.put("edge", idx);
                    at.kids().put(l.pos() + "~" + String.format("%08d", idx), new Step(mark, new TreeMap<>()));
                    truncated.add(endpoint + ": paths to edge " + idx + " (" + to + ") cut at "
                            + l.jsonPath().stream().map(j -> String.valueOf(j.get("label"))).collect(Collectors.joining(" > "))
                            + ", only the first " + FlowTracer.MAX_FLOW_PATHS + " per call are in its flow");
                    continue;
                }
                String key = l.pos() + "~" + String.format("%08d", idx);
                Map<String, Object> leaf = node(to.startsWith("unresolved:") ? "unresolved" : "edge", null);
                leaf.put("edge", idx);
                at.kids().put(key, new Step(leaf, new TreeMap<>()));
                // the call leads back to this endpoint through other services' edges
                if (reaches(to, endpoint, next)) {
                    at.kids().put(key + "~cycle", new Step(node("cycle", "↻ back to " + endpoint), new TreeMap<>()));
                }
            }
            if (!root.kids().isEmpty()) {
                out.put(endpoint, json(root));
            }
        });
        return out;
    }

    private static boolean reaches(String from, String target, Map<String, List<String>> next) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (n.equals(target)) {
                return true;
            }
            if (seen.add(n)) {
                queue.addAll(next.getOrDefault(n, List.of()));
            }
        }
        return false;
    }

    private static Map<String, Object> json(Step s) {
        Map<String, Object> j = new LinkedHashMap<>(s.json());
        if (!s.kids().isEmpty()) {
            j.put("children", s.kids().values().stream().map(FlowTree::json).toList());
        }
        return j;
    }

    /** Control structures between {@code call} and its method (or the handler lambda), outermost first. */
    private void controls(Node call, Node handler, List<String> keys, List<Map<String, Object>> json) {
        List<String> k = new ArrayList<>();
        List<Map<String, Object>> j = new ArrayList<>();
        Node ch = call;
        while (ch != handler && !(ch instanceof MethodDeclaration) && ch.getParentNode().isPresent()) {
            Node p = ch.getParentNode().get();
            Node child = ch;
            String type = null;
            String label = null;
            Node at = ch;
            if (p instanceof IfStmt s && ch == s.getThenStmt() || p instanceof ConditionalExpr c && ch == c.getThenExpr()) {
                type = "if";
                label = text(p instanceof IfStmt s ? s.getCondition() : ((ConditionalExpr) p).getCondition());
            } else if (p instanceof IfStmt s && s.getElseStmt().filter(e -> e == child).isPresent()
                    || p instanceof ConditionalExpr c && ch == c.getElseExpr()) {
                type = "else"; // the negated condition: the if side may hold no edge and be left out
                label = "!(" + text(p instanceof IfStmt s ? s.getCondition() : ((ConditionalExpr) p).getCondition()) + ")";
            } else if (p instanceof SwitchEntry e && !e.getLabels().contains(ch)) {
                type = "case";
                label = e.getLabels().isEmpty() ? "default" : e.getLabels().stream().map(FlowTree::text).collect(Collectors.joining(", "));
            } else if (p instanceof SwitchStmt s && ch instanceof SwitchEntry) {
                type = "switch";
                label = text(s.getSelector());
                at = p; // one switch node whichever case
            } else if (p instanceof SwitchExpr s && ch instanceof SwitchEntry) {
                type = "switch";
                label = text(s.getSelector());
                at = p;
            } else if (p instanceof ForStmt s && ch == s.getBody()) {
                type = "loop";
                label = "for (" + s.getInitialization().stream().map(FlowTree::text).collect(Collectors.joining(", ")) + "; "
                        + s.getCompare().map(FlowTree::text).orElse("") + "; "
                        + s.getUpdate().stream().map(FlowTree::text).collect(Collectors.joining(", ")) + ")";
            } else if (p instanceof ForEachStmt s && ch == s.getBody()) {
                type = "loop";
                label = "for (" + text(s.getVariable()) + " : " + text(s.getIterable()) + ")";
            } else if (p instanceof WhileStmt s && ch == s.getBody()) {
                type = "loop";
                label = "while (" + text(s.getCondition()) + ")";
            } else if (p instanceof DoStmt s && ch == s.getBody()) {
                type = "loop";
                label = "do ... while (" + text(s.getCondition()) + ")";
            } else if (p instanceof TryStmt s && ch == s.getTryBlock()) {
                type = "try";
            } else if (p instanceof TryStmt s && s.getFinallyBlock().filter(b -> b == child).isPresent()) {
                type = "try"; // PLAN has no finally type: a try labelled "finally"
                label = "finally";
            } else if (p instanceof CatchClause c && ch == c.getBody()) {
                type = "catch";
                label = text(c.getParameter().getType());
            } else if (p instanceof MethodCallExpr m && streamLoop(m) && (ch instanceof LambdaExpr || ch instanceof MethodReferenceExpr)
                    && m.getArguments().contains(ch)) {
                type = "loop";
                label = m.getScope().map(s -> text(s).replaceAll("\\s+\\.", ".") + ".").orElse("") + m.getNameAsString();
            }
            if (type == null && p instanceof MethodCallExpr m) {
                reactive(m, ch, k, j);
            }
            if (type == null && p instanceof com.github.javaparser.ast.body.VariableDeclarator v && v.getInitializer().filter(i -> i == child).isPresent()) {
                zippedVariable(v, k, j); // Mono<..> a = call(); ... Mono.zip(a, b)
            }
            if (type != null) {
                k.add(0, pos(at) + ":" + type + ":" + label);
                j.add(0, node(type, label));
            }
            ch = p;
        }
        keys.addAll(k);
        json.addAll(j);
    }

    private static final Set<String> FLUX_LOOP = Set.of("flatMap", "concatMap", "flatMapSequential", "map");
    /** Library calls that return a Flux: WebClient, DatabaseClient fetch().all(), reactive Redis ops, Flux sources. */
    private static final Set<String> FLUX_SOURCES = Set.of("bodyToFlux", "all", "thenMany", "flatMapMany", "flatMapIterable", "members",
            "range", "rangeByScore", "reverseRange", "reverseRangeByScore", "scan", "entries", "values", "keys");
    private static final Set<String> FALLBACK = Set.of("switchIfEmpty", "defaultIfEmpty", "onErrorResume", "onErrorReturn");
    /** Operators that keep a Flux a Flux, followed back up a chain to find its source. */
    private static final Set<String> FLUX_KEEP = Set.of("flatMap", "concatMap", "flatMapSequential", "map", "filter", "take", "skip",
            "distinct", "sort", "switchIfEmpty", "defaultIfEmpty", "onErrorResume", "onErrorReturn", "retry", "retryWhen",
            "doOnNext", "doOnError", "log", "delayElements", "timeout", "concatWith", "mergeWith");

    /** PLAN section 4 reactive operators around {@code ch}, which sits in the scope or an argument of {@code m}. */
    // keys use the operator name position: a chained call begins where its whole chain begins
    private void reactive(MethodCallExpr m, Node ch, List<String> k, List<Map<String, Object>> j) {
        String name = m.getNameAsString();
        if (m.getScope().filter(s -> s == ch).isPresent()) {
            if (name.equals("retry") || name.equals("retryWhen")) { // everything upstream is retried
                k.add(0, pos(m.getName()) + ":retry:" + name);
                j.add(0, node("retry", name + "(" + m.getArguments().stream().map(FlowTree::text).collect(Collectors.joining(", ")) + ")"));
            }
            return;
        }
        if (!m.getArguments().contains(ch)) {
            return;
        }
        String scope = m.getScope().map(FlowTree::text).orElse("");
        boolean fn = ch instanceof LambdaExpr || ch instanceof MethodReferenceExpr;
        if (zip(m)) {
            k.add(0, pos(m.getName()) + ":parallel:" + name);
            j.add(0, node("parallel", scope.replaceAll(".*\\.", "") + "." + name));
        } else if (FALLBACK.contains(name) && (fn || name.equals("switchIfEmpty") || name.equals("onErrorResume"))) {
            // defaultIfEmpty(x) / onErrorReturn(x) evaluate x eagerly: a call there always runs, so no branch
            k.add(0, pos(m.getName()) + ":fallback:" + name);
            j.add(0, node("fallback", name));
        } else if (name.equals("filter") && fn) {
            k.add(0, pos(m.getName()) + ":if:filter");
            j.add(0, node("if", "filter(" + text(ch) + ")"));
        } else if (FLUX_LOOP.contains(name) && fn) {
            if (m.getScope().filter(this::flux).isPresent()) {
                k.add(0, pos(m.getName()) + ":loop:" + name);
                j.add(0, node("loop", chain(m.getScope().get()) + "." + name)); // skus.flatMap(..).concatMap
            }
            // an upstream filter decides whether this runs at all
            for (Node n = m.getScope().orElse(null); n instanceof MethodCallExpr c && FLUX_KEEP.contains(c.getNameAsString())
                    && !FALLBACK.contains(c.getNameAsString()); n = c.getScope().orElse(null)) {
                if (c.getNameAsString().equals("filter") && c.getArguments().size() == 1) {
                    k.add(0, pos(c.getName()) + ":if:filter");
                    j.add(0, node("if", "filter(" + text(c.getArgument(0)) + ")"));
                }
            }
        }
    }

    /** Whether {@code e} is a Flux: by its declared type (variable, parameter, method), or a Flux.x(..) source up its chain. */
    private boolean flux(Node e) {
        if (e instanceof MethodCallExpr c) {
            if (c.getScope().filter(s -> s.toString().matches("(reactor\\.core\\.publisher\\.)?Flux")).isPresent()) {
                return true;
            }
            if (FLUX_SOURCES.contains(c.getNameAsString()) || c.getNameAsString().startsWith("findAll")
                    || returnTypes.apply(c).stream().anyMatch(FlowTree::fluxType)) {
                return true;
            }
            return FLUX_KEEP.contains(c.getNameAsString()) && c.getScope().filter(this::flux).isPresent();
        }
        String name = e instanceof com.github.javaparser.ast.expr.NameExpr n ? n.getNameAsString()
                : e instanceof com.github.javaparser.ast.expr.FieldAccessExpr fa && fa.getScope().isThisExpr() ? fa.getNameAsString() : null;
        if (name != null) { // parameter, local (also var) or field, by scope; a this.x skips locals only in theory
            Node d = com.endpointmap.workspace.Names.declaration(name, e).orElse(null);
            if (d instanceof com.github.javaparser.ast.body.Parameter prm) {
                return fluxType(prm.getType().asString());
            }
            if (d instanceof com.github.javaparser.ast.body.VariableDeclarator v) {
                return v.getType().isVarType() ? v.getInitializer().filter(this::flux).isPresent() : fluxType(v.getType().asString());
            }
        }
        return false;
    }

    private static boolean fluxType(String t) {
        return t.matches("(reactor\\.core\\.publisher\\.)?Flux(<.*)?");
    }

    private static boolean zip(MethodCallExpr m) {
        return (m.getNameAsString().equals("zip") || m.getNameAsString().equals("when"))
                && m.getScope().filter(s -> s.toString().matches("(reactor\\.core\\.publisher\\.)?(Mono|Flux)")).isPresent();
    }

    /** {@code v} used (as the start of a chain) as an argument of a zip/when in its method: a parallel there. */
    private static void zippedVariable(com.github.javaparser.ast.body.VariableDeclarator v, List<String> k, List<Map<String, Object>> j) {
        Node body = v;
        while (body.getParentNode().isPresent() && !(body instanceof MethodDeclaration || body instanceof LambdaExpr)) {
            body = body.getParentNode().get();
        }
        for (var use : body.findAll(com.github.javaparser.ast.expr.NameExpr.class, u -> u.getNameAsString().equals(v.getNameAsString()))) {
            if (com.endpointmap.workspace.Names.declaration(use.getNameAsString(), use).filter(d -> d == v).isEmpty()) {
                continue;
            }
            Node n = use;
            while (n.getParentNode().orElse(null) instanceof MethodCallExpr c && c.getScope().orElse(null) == n) {
                n = c;
            }
            Node arg = n;
            if (n.getParentNode().orElse(null) instanceof MethodCallExpr z && zip(z) && z.getArguments().stream().anyMatch(a -> a == arg)) {
                k.add(0, pos(z.getName()) + ":parallel:" + z.getNameAsString());
                j.add(0, node("parallel", z.getScope().get().toString().replaceAll(".*\\.", "") + "." + z.getNameAsString()));
                return;
            }
        }
    }

    /** A loop label's source: the chain up to {@code e}, arguments elided, the source call (Flux.fromIterable(ids)) in full. */
    private static String chain(Node e) {
        if (e instanceof MethodCallExpr c && c.getScope().isPresent() && !c.getScope().get().toString().matches("([a-z_$][\\w$]*\\.)*[A-Z][\\w$]*")) {
            return chain(c.getScope().get()) + "." + c.getNameAsString() + (c.getArguments().isEmpty() ? "()" : "(..)");
        }
        return text(e);
    }

    /** {@code x.forEach(..)}, or {@code map/flatMap} on a Java stream: each element runs the lambda. */
    private static boolean streamLoop(MethodCallExpr m) {
        String name = m.getNameAsString();
        if (name.equals("forEach") || name.equals("forEachOrdered")) {
            return true;
        }
        if (!name.equals("map") && !name.equals("flatMap")) {
            return false;
        }
        // a Java stream somewhere up the chain: list.stream(), parallelStream(), Stream.of(..); not Flux.fromStream(..)
        for (Node n = m.getScope().orElse(null); n instanceof MethodCallExpr c; n = c.getScope().orElse(null)) {
            String cn = c.getNameAsString();
            if (cn.equals("stream") || cn.equals("parallelStream")
                    || c.getScope().filter(sc -> sc.toString().matches("(Int|Long|Double)?Stream")).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> node(String type, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (label != null) {
            m.put("label", label);
        }
        return m;
    }

    /** Original source text, whitespace collapsed. */
    private static String text(Node n) {
        return n.getTokenRange().map(Object::toString).orElseGet(n::toString).replaceAll("\\s+", " ").trim();
    }

    private static String pos(Node n) {
        return n.getBegin().map(p -> String.format("%06d:%06d", p.line, p.column)).orElse("000000:000000");
    }
}
