package com.endpointmap.workspace;

import com.endpointmap.workspace.Workspace.Repo;
import com.endpointmap.workspace.Workspace.SourceFile;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps type references back to the workspace's own declarations. The symbol solver set up by
 * {@link WorkspaceLoader} answers first (Java's import/package rules); a type with that qualified name in the
 * same repo wins over one in another repo. When the solver fails, the simple name is used, same repo first.
 */
public final class Types {

    private final Map<CompilationUnit, Repo> repoOf = new IdentityHashMap<>();
    private final Map<CompilationUnit, String> relPathOf = new IdentityHashMap<>();
    private final Map<Path, CompilationUnit> byFile = new HashMap<>();
    private final Map<String, List<TypeDeclaration<?>>> byQualifiedName = new LinkedHashMap<>();
    private final Map<String, List<TypeDeclaration<?>>> bySimpleName = new LinkedHashMap<>();

    public Types(Workspace ws) {
        for (Repo repo : ws.repos()) {
            for (SourceFile sf : repo.sources()) {
                repoOf.put(sf.cu(), repo);
                relPathOf.put(sf.cu(), sf.relPath());
                sf.cu().getStorage().ifPresent(st -> byFile.put(st.getPath(), sf.cu()));
                for (TypeDeclaration<?> t : sf.cu().findAll(TypeDeclaration.class)) {
                    t.getFullyQualifiedName().ifPresent(q -> byQualifiedName.computeIfAbsent(q, k -> new ArrayList<>()).add(t));
                    bySimpleName.computeIfAbsent(t.getNameAsString(), k -> new ArrayList<>()).add(t);
                }
            }
        }
    }

    /** The declaration a type reference (extends/implements/field type) points to. */
    public Optional<TypeDeclaration<?>> resolve(ClassOrInterfaceType type) {
        String qualifiedName;
        try {
            qualifiedName = type.resolve().asReferenceType().getQualifiedName();
        } catch (RuntimeException | StackOverflowError e) {
            return bySimpleNameNear(type.getNameAsString(), type);
        }
        return pick(byQualifiedName.get(qualifiedName), type); // a JDK type, say: not ours
    }

    /**
     * The type that declares the field a {@code NameExpr}/{@code FieldAccessExpr} reads, by the solver only.
     * Empty when the solver can't tell; callers then fall back to their own lookup.
     */
    public Optional<TypeDeclaration<?>> declaringTypeOfField(Expression e) {
        if (!(e instanceof NameExpr || e instanceof FieldAccessExpr)) {
            return Optional.empty();
        }
        try {
            ResolvedValueDeclaration v = e instanceof NameExpr n ? n.resolve() : ((FieldAccessExpr) e).resolve();
            return v.isField() ? pick(byQualifiedName.get(v.asField().declaringType().getQualifiedName()), e) : Optional.empty();
        } catch (RuntimeException | StackOverflowError ex) {
            return Optional.empty();
        }
    }

    // shortcut: name fallback ignores imports and packages; mark results confidence "low" once edges carry it
    public Optional<TypeDeclaration<?>> bySimpleNameNear(String simpleName, Node context) {
        return pick(bySimpleName.get(simpleName), context);
    }

    /**
     * The workspace's own copy of a method the symbol solver returned: the solver parses files again, so its nodes
     * are not the ones the workspace holds. Matched by file and position; the method itself when already ours.
     */
    public MethodDeclaration canonical(MethodDeclaration m) {
        Optional<CompilationUnit> cu = m.findCompilationUnit();
        if (cu.isEmpty() || repoOf.containsKey(cu.get())) {
            return m;
        }
        return cu.get().getStorage().map(st -> byFile.get(st.getPath()))
                .flatMap(own -> own.findFirst(MethodDeclaration.class, o -> o.getRange().equals(m.getRange())))
                .orElse(m);
    }

    /** Workspace-relative path of the file that holds {@code node}, or "" for nodes outside the workspace. */
    public String relPath(Node node) {
        return node.findCompilationUnit().map(relPathOf::get).orElse("");
    }

    private Optional<TypeDeclaration<?>> pick(List<TypeDeclaration<?>> candidates, Node context) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        Repo repo = context.findCompilationUnit().map(repoOf::get).orElse(null);
        return candidates.stream().filter(t -> t.findCompilationUnit().map(repoOf::get).orElse(null) == repo)
                .findFirst().or(() -> Optional.of(candidates.get(0)));
    }
}
