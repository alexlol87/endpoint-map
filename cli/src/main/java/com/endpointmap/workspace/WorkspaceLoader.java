package com.endpointmap.workspace;

import com.endpointmap.workspace.Workspace.Kind;
import com.endpointmap.workspace.Workspace.ParseFailure;
import com.endpointmap.workspace.Workspace.Repo;
import com.endpointmap.workspace.Workspace.SourceFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Every sub-folder of the root is a repo; folders without Java sources (terraform, python) are skipped.
 * Sources are parsed with a symbol solver over the JDK plus every repo's {@code src/main/java} (see {@link Types}).
 */
public final class WorkspaceLoader {

    private static final ParserConfiguration.LanguageLevel LANGUAGE_LEVEL = ParserConfiguration.LanguageLevel.JAVA_25;

    private WorkspaceLoader() {
    }

    public static Workspace load(Path root) {
        Map<Path, List<Path>> javaFilesByRepo = new LinkedHashMap<>();
        for (Path dir : list(root, Files::isDirectory)) {
            List<Path> javaFiles = dir.getFileName().toString().startsWith(".") ? List.of() : javaFiles(dir);
            if (!javaFiles.isEmpty()) {
                javaFilesByRepo.put(dir, javaFiles);
            }
        }
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver());
        ParserConfiguration solverConfig = new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL);
        javaFilesByRepo.forEach((dir, files) -> sourceRoots(dir, files)
                .forEach(r -> solver.add(new JavaParserTypeSolver(r, solverConfig))));
        // the solver re-parses files it resolves through (record accessors need it), so it needs a resolver too
        solverConfig.setSymbolResolver(new JavaSymbolSolver(solver));
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL)
                .setSymbolResolver(new JavaSymbolSolver(solver)));
        List<Repo> repos = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        for (Map.Entry<Path, List<Path>> entry : javaFilesByRepo.entrySet()) {
            Path dir = entry.getKey();
            String name = dir.getFileName().toString();
            List<Path> javaFiles = entry.getValue();
            List<SourceFile> sources = new ArrayList<>();
            for (Path file : javaFiles) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                try {
                    ParseResult<CompilationUnit> result = parser.parse(file);
                    if (result.isSuccessful() && result.getResult().isPresent()) {
                        sources.add(new SourceFile(rel, result.getResult().get()));
                    } else {
                        String error = result.getProblems().isEmpty()
                                ? "unknown parse error" : result.getProblems().get(0).getVerboseMessage();
                        failures.add(new ParseFailure(rel, error.lines().findFirst().orElse(error)));
                    }
                } catch (IOException | RuntimeException e) {
                    failures.add(new ParseFailure(rel, String.valueOf(e.getMessage())));
                }
            }
            repos.add(new Repo(name, dir, isService(sources) ? Kind.service : Kind.library, sources));
        }
        return new Workspace(root, repos, failures);
    }

    private static boolean isService(List<SourceFile> sources) {
        return sources.stream()
                .flatMap(s -> s.cu().findAll(TypeDeclaration.class).stream())
                .anyMatch(t -> Workspace.hasAnnotation(t, "SpringBootApplication"));
    }

    /** Each {@code .../src/main/java} that holds a file; the repo folder itself for files outside one. */
    private static List<Path> sourceRoots(Path repoDir, List<Path> javaFiles) {
        TreeSet<Path> roots = new TreeSet<>();
        for (Path f : javaFiles) {
            Path r = f.getParent();
            while (r != null && r.startsWith(repoDir) && !r.endsWith(Path.of("src", "main", "java"))) {
                r = r.getParent();
            }
            roots.add(r != null && r.startsWith(repoDir) ? r : repoDir);
        }
        return List.copyOf(roots);
    }

    private static List<Path> javaFiles(Path repoDir) {
        try (Stream<Path> walk = Files.walk(repoDir)) {
            return walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .filter(p -> {
                        String rel = repoDir.relativize(p).toString().replace('\\', '/');
                        return !rel.startsWith("target/") && !rel.contains("/target/")
                                && !rel.contains("src/test/");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> list(Path dir, java.util.function.Predicate<Path> filter) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(filter).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
