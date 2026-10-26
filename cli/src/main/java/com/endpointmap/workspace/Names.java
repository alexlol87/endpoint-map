package com.endpointmap.workspace;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.nodeTypes.NodeWithStatements;
import java.util.List;
import java.util.Optional;

/**
 * What a simple name refers to at a point in the code, by Java's scope rules: a local declared earlier in an
 * enclosing block (or a for/try/catch/lambda header), then a parameter of the enclosing method or lambda, then a
 * field of an enclosing type. Sibling blocks and later declarations are not visible.
 */
public final class Names {

    private Names() {
    }

    /** The {@link Parameter} or {@link VariableDeclarator} (local or field) {@code name} means at {@code at}. */
    public static Optional<Node> declaration(String name, Node at) {
        Node child = at;
        for (Node n = at.getParentNode().orElse(null); n != null; child = n, n = n.getParentNode().orElse(null)) {
            Optional<Node> found = Optional.empty();
            if (n instanceof BlockStmt || n instanceof SwitchEntry) {
                found = before(((NodeWithStatements<?>) n).getStatements(), child, name);
            } else if (n instanceof VariableDeclarationExpr v) { // String a = "x", b = a;
                found = v.getVariables().stream().filter(d -> d.getNameAsString().equals(name) && ends(d, at))
                        .map(Node.class::cast).reduce((x, y) -> y);
            } else if (n instanceof ForStmt f && !f.getInitialization().contains(child)) {
                found = declared(f.getInitialization(), name);
            } else if (n instanceof ForEachStmt f && child != f.getIterable()) {
                found = declared(List.of(f.getVariable()), name);
            } else if (n instanceof TryStmt t && !t.getResources().contains(child)) {
                found = declared(t.getResources(), name);
            } else if (n instanceof CatchClause c) {
                found = param(c.getParameter(), name);
            } else if (n instanceof LambdaExpr l) {
                found = l.getParameters().stream().filter(p -> p.getNameAsString().equals(name)).map(Node.class::cast).findFirst();
            } else if (n instanceof CallableDeclaration<?> cd) {
                found = cd.getParameters().stream().filter(p -> p.getNameAsString().equals(name)).map(Node.class::cast).findFirst();
            } else if (n instanceof TypeDeclaration<?> td) {
                found = td.getFieldByName(name).flatMap(fd -> fd.getVariables().stream()
                        .filter(v -> v.getNameAsString().equals(name)).findFirst());
            }
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** A local declared by a statement of the block that comes before the one holding the use. */
    private static Optional<Node> before(List<Statement> statements, Node child, String name) {
        Optional<Node> out = Optional.empty();
        for (Statement s : statements) {
            if (s == child) {
                break;
            }
            if (s instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr v) {
                Optional<Node> d = declared(List.of(v), name);
                out = d.isPresent() ? d : out;
            }
        }
        return out;
    }

    private static Optional<Node> declared(List<? extends Expression> exprs, String name) {
        return exprs.stream().filter(VariableDeclarationExpr.class::isInstance)
                .flatMap(e -> ((VariableDeclarationExpr) e).getVariables().stream())
                .filter(v -> v.getNameAsString().equals(name)).map(Node.class::cast).findFirst();
    }

    private static Optional<Node> param(Parameter p, String name) {
        return p.getNameAsString().equals(name) ? Optional.of(p) : Optional.empty();
    }

    private static boolean ends(Node d, Node use) {
        return d.getEnd().isPresent() && use.getBegin().isPresent() && d.getEnd().get().isBefore(use.getBegin().get());
    }
}
