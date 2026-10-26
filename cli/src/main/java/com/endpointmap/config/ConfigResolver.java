package com.endpointmap.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Flat Spring properties of one repo for one profile, from src/main/resources:
 * application.yml/.yaml/.properties, then documents activated for the profile, then
 * application-{profile}.*; later sources win. Placeholders are kept as written; {@link #resolve} expands them.
 */
public final class ConfigResolver {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigResolver() {
    }

    /** Files that could not be read are added to {@code problems} and skipped. */
    public static Map<String, String> load(Path repoDir, String profile, List<String> problems) {
        return load(repoDir, profile, problems, new TreeMap<>());
    }

    /** As {@link #load(Path, String, List)}, also filling {@code sources}: key -> the file name that set it. */
    public static Map<String, String> load(Path repoDir, String profile, List<String> problems, Map<String, String> sources) {
        Path res = repoDir.resolve("src/main/resources");
        Map<String, String> props = new TreeMap<>();
        List<Map<String, String>> profileDocs = new ArrayList<>();
        List<String> profileDocFiles = new ArrayList<>();
        for (String ext : List.of("yml", "yaml", "properties")) {
            String name = "application." + ext;
            for (Map<String, String> doc : read(res.resolve(name), problems)) {
                String onProfile = doc.getOrDefault("spring.config.activate.on-profile", doc.get("spring.profiles"));
                if (onProfile == null) {
                    put(props, doc, sources, name);
                } else if (profile != null && Arrays.stream(onProfile.split(","))
                        .map(String::trim).anyMatch(profile::equals)) {
                    profileDocs.add(doc);
                    profileDocFiles.add(name);
                }
            }
        }
        for (int i = 0; i < profileDocs.size(); i++) {
            put(props, profileDocs.get(i), sources, profileDocFiles.get(i));
        }
        if (profile != null) {
            for (String ext : List.of("yml", "yaml", "properties")) {
                String name = "application-" + profile + "." + ext;
                read(res.resolve(name), problems).forEach(doc -> put(props, doc, sources, name));
            }
        }
        return props;
    }

    private static void put(Map<String, String> props, Map<String, String> doc, Map<String, String> sources, String file) {
        props.putAll(doc);
        doc.keySet().forEach(k -> sources.put(k, file));
    }

    private static List<Map<String, String>> read(Path file, List<String> problems) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            List<Map<String, String>> docs = new ArrayList<>();
            if (file.toString().endsWith(".properties")) {
                Properties p = new Properties();
                p.load(reader);
                Map<String, String> doc = new TreeMap<>();
                p.stringPropertyNames().forEach(k -> doc.put(k, p.getProperty(k)));
                docs.add(doc);
            } else {
                try (MappingIterator<JsonNode> it = YAML.readerFor(JsonNode.class).readValues(reader)) {
                    while (it.hasNext()) {
                        Map<String, String> doc = new TreeMap<>();
                        flatten("", it.next(), doc);
                        docs.add(doc);
                    }
                }
            }
            return docs;
        } catch (IOException | RuntimeException e) {
            // shortcut: reported on stdout only, list it in discover-report.md once that exists
            problems.add(file + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            return List.of();
        }
    }

    /** The prefix every inbound path gets: {@code server.servlet.context-path}, else {@code spring.webflux.base-path}. */
    public static String basePath(Map<String, String> config) {
        return resolve(config.getOrDefault("server.servlet.context-path", config.getOrDefault("spring.webflux.base-path", "")), config);
    }

    /**
     * Expands {@code ${key}} and {@code ${key:default}} from {@code props}, then the default; a placeholder with
     * neither (an environment variable, say) becomes {@code {*}}.
     */
    public static String resolve(String value, Map<String, String> props) {
        return resolve(value, props, 0);
    }

    private static String resolve(String value, Map<String, String> props, int depth) {
        int start = value.indexOf("${");
        if (start < 0 || depth > 10) {
            return value;
        }
        int nested = 0;
        int end = -1;
        int colon = -1;
        for (int i = start + 2; i < value.length() && end < 0; i++) {
            char c = value.charAt(i);
            if (c == '{') {
                nested++;
            } else if (c == '}' && nested-- == 0) {
                end = i;
            } else if (c == ':' && nested == 0 && colon < 0) {
                colon = i;
            }
        }
        if (end < 0) {
            return value;
        }
        String key = value.substring(start + 2, colon < 0 ? end : colon);
        String found = props.containsKey(key) ? props.get(key) : colon < 0 ? "{*}" : value.substring(colon + 1, end);
        return value.substring(0, start) + resolve(found, props, depth + 1) + resolve(value.substring(end + 1), props, depth + 1);
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(e -> flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        } else {
            out.put(prefix, node.asText());
        }
    }
}
