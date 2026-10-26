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

    private record Leaf(Map<String, Object> edge, String pos, List<String> keyPath, List<Map<String, Object>> jsonPath) {
    }

    private final Function<String, Node> sites;
    private final Map<String, List<Leaf>> leaves = new TreeMap<>();

    /** {@code sites}: the call expression at a {@link FlowTracer.Found#sites()} entry. */
    public FlowTree(Function<String, Node> sites) {
        this.sites = sites;
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
        for (int i = 0; i < f.sites().size(); i++) {
            Node call = sites.apply(f.sites().get(i));
            if (call == null) {
                throw new IllegalStateException("no call recorded at " + f.sites().get(i));
            }
            controls(call, handler, keys, json);
            last = pos(call);
            if (i < f.sites().size() - 1) {
                keys.add(pos(call) + ":call:" + f.via().get(i + offset));
                json.add(node("call", f.via().get(i + offset)));
            }
        }
        leaves.computeIfAbsent(endpoint, k -> new ArrayList<>()).add(new Leaf(edge, last, keys, json));
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
    private static void controls(Node call, Node handler, List<String> keys, List<Map<String, Object>> json) {
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
                type = "else"; // the condition it is the else of: the if side may hold no edge and be left out
                label = text(p instanceof IfStmt s ? s.getCondition() : ((ConditionalExpr) p).getCondition());
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
            if (type != null) {
                k.add(0, pos(at) + ":" + type + ":" + label);
                j.add(0, node(type, label));
            }
            ch = p;
        }
        keys.addAll(k);
        json.addAll(j);
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
