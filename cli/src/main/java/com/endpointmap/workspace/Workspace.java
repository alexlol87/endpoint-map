package com.endpointmap.workspace;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.nio.file.Path;
import java.util.List;

/** All Java repos under the root, parsed once. Repos are sorted by name. */
public record Workspace(Path root, List<Repo> repos, List<ParseFailure> parseFailures) {

    public enum Kind { service, library }

    public record Repo(String name, Path dir, Kind kind, List<SourceFile> sources) {
    }

    /** {@code relPath} is relative to the workspace root, with forward slashes. */
    public record SourceFile(String relPath, CompilationUnit cu) {
    }

    public record ParseFailure(String relPath, String error) {
    }

    /** Matches by simple name, so both {@code @RestController} and {@code @org...RestController} count. */
    public static boolean hasAnnotation(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals(simpleName));
    }
}
