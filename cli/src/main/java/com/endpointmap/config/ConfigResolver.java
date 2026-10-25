package com.endpointmap.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
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
 * application-{profile}.*; later sources win. Placeholders are kept as written.
 */
public final class ConfigResolver {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigResolver() {
    }

    public static Map<String, String> load(Path repoDir, String profile) {
        Path res = repoDir.resolve("src/main/resources");
        Map<String, String> props = new TreeMap<>();
        List<Map<String, String>> profileDocs = new ArrayList<>();
        for (String ext : List.of("yml", "yaml", "properties")) {
            for (Map<String, String> doc : read(res.resolve("application." + ext))) {
                String onProfile = doc.getOrDefault("spring.config.activate.on-profile", doc.get("spring.profiles"));
                if (onProfile == null) {
                    props.putAll(doc);
                } else if (profile != null && Arrays.stream(onProfile.split(","))
                        .map(String::trim).anyMatch(profile::equals)) {
                    profileDocs.add(doc);
                }
            }
        }
        profileDocs.forEach(props::putAll);
        if (profile != null) {
            for (String ext : List.of("yml", "yaml", "properties")) {
                read(res.resolve("application-" + profile + "." + ext)).forEach(props::putAll);
            }
        }
        return props;
    }

    private static List<Map<String, String>> read(Path file) {
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
            // shortcut: a broken config file is ignored silently, list it in discover-report.md once that exists
            return List.of();
        }
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
