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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Every sub-folder of the root is a repo; folders without Java sources (terraform, python) are skipped. */
public final class WorkspaceLoader {

    private WorkspaceLoader() {
    }

    public static Workspace load(Path root) {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25);
        JavaParser parser = new JavaParser(config);
        List<Repo> repos = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        for (Path dir : list(root, Files::isDirectory)) {
            String name = dir.getFileName().toString();
            if (name.startsWith(".")) {
                continue;
            }
            List<Path> javaFiles = javaFiles(dir);
            if (javaFiles.isEmpty()) {
                continue;
            }
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
