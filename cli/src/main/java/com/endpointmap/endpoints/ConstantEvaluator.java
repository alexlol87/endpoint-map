package com.endpointmap.endpoints;

import com.endpointmap.workspace.Types;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.Optional;

/** Evaluates compile-time String expressions: literals, concatenation and static final constants. */
public final class ConstantEvaluator {

    private static final int MAX_DEPTH = 10;

    private final Types types;

    public ConstantEvaluator(Types types) {
        this.types = types;
    }

    public Optional<String> eval(Expression e, Node context) {
        return eval(e, context, 0);
    }

    private Optional<String> eval(Expression e, Node context, int depth) {
        if (depth > MAX_DEPTH) {
            return Optional.empty();
        }
        if (e instanceof StringLiteralExpr s) {
            return Optional.of(s.asString());
        }
        if (e instanceof com.github.javaparser.ast.expr.TextBlockLiteralExpr t) {
            return Optional.of(t.asString());
        }
        if (e instanceof EnclosedExpr en) {
            return eval(en.getInner(), context, depth + 1);
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> l = eval(b.getLeft(), context, depth + 1);
            Optional<String> r = eval(b.getRight(), context, depth + 1);
            return l.isPresent() && r.isPresent() ? Optional.of(l.get() + r.get()) : Optional.empty();
        }
        Optional<TypeDeclaration<?>> declaring = types.declaringTypeOfField(e);
        if (declaring.isPresent()) {
            return field(declaring.get(), e instanceof NameExpr n ? n.getNameAsString()
                    : ((FieldAccessExpr) e).getNameAsString(), depth);
        }
        if (e instanceof NameExpr n) {
            for (Node t = context; t != null; t = t.getParentNode().orElse(null)) {
                if (t instanceof TypeDeclaration<?> td) {
                    Optional<String> v = field(td, n.getNameAsString(), depth);
                    if (v.isPresent()) {
                        return v;
                    }
                }
            }
            return Optional.empty();
        }
        if (e instanceof FieldAccessExpr fa) {
            String owner = fa.getScope() instanceof NameExpr sn ? sn.getNameAsString()
                    : fa.getScope() instanceof FieldAccessExpr sf ? sf.getNameAsString() : null;
            return owner == null ? Optional.empty()
                    : types.bySimpleNameNear(owner, e).flatMap(td -> field(td, fa.getNameAsString(), depth));
        }
        return Optional.empty();
    }

    private Optional<String> field(TypeDeclaration<?> td, String name, int depth) {
        for (FieldDeclaration f : td.getFields()) {
            // interface fields are implicitly static final
            boolean constant = td.isClassOrInterfaceDeclaration() && td.asClassOrInterfaceDeclaration().isInterface()
                    || f.isStatic() && f.isFinal();
            if (!constant) {
                continue;
            }
            List<VariableDeclarator> vars = f.getVariables();
            for (VariableDeclarator v : vars) {
                if (v.getNameAsString().equals(name) && v.getInitializer().isPresent()) {
                    return eval(v.getInitializer().get(), td, depth + 1);
                }
            }
        }
        return Optional.empty();
    }
}
