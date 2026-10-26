package com.endpointmap.resolutions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The resolutions file (PLAN.md section 3): per service, {@code properties} and {@code callSites}, each mapping a key
 * to {@code service:<repo>}, {@code external:<https://host>} or blank. Parsed with the YAML library; written by hand
 * so the order is fixed and the usage/hint comments can be regenerated on every discover.
 */
public final class Resolutions {

    public static final String PROPERTIES = "properties";
    public static final String CALL_SITES = "callSites";
    private static final List<String> SECTIONS = List.of(PROPERTIES, CALL_SITES);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern VALUE = Pattern.compile("|service:\\S+|external:https?://[^/\\s]+\\S*");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)*");

    /** A key found in code this run: where it is used ({@code usages}, sorted) and what code showed ({@code hint}). */
    public record Found(String service, String section, String key, String hint, Set<String> usages) {
    }

    public record Stats(int entries, int blank, int added, int stale) {
    }

    /** service -> section -> key -> value ("" when blank). */
    private final Map<String, Map<String, Map<String, String>>> values;

    private Resolutions(Map<String, Map<String, Map<String, String>>> values) {
        this.values = values;
    }

    public static Resolutions empty() {
        return new Resolutions(new TreeMap<>());
    }

    /** A missing file is empty. Anything this class can't write back unchanged is refused, so a rewrite never loses edits. */
    public static Resolutions read(Path file) throws IOException {
        Map<String, Map<String, Map<String, String>>> values = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return new Resolutions(values);
        }
        JsonNode root = YAML.readTree(file.toFile());
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new Resolutions(values);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException(file + ": expected service names at the top level");
        }
        for (Map.Entry<String, JsonNode> service : root.properties()) {
            Map<String, Map<String, String>> sections = values.computeIfAbsent(service.getKey(), k -> new TreeMap<>());
            if (service.getValue().isNull()) {
                continue;
            }
            if (!service.getValue().isObject()) {
                throw new IllegalArgumentException(file + ": " + service.getKey() + " must hold properties/callSites");
            }
            for (Map.Entry<String, JsonNode> section : service.getValue().properties()) {
                if (!SECTIONS.contains(section.getKey())
                        || !(section.getValue().isObject() || section.getValue().isNull())) {
                    throw new IllegalArgumentException(file + ": " + service.getKey() + "." + section.getKey()
                            + " is not a properties or callSites mapping");
                }
                Map<String, String> entries = sections.computeIfAbsent(section.getKey(), k -> new TreeMap<>());
                for (Map.Entry<String, JsonNode> e : section.getValue().properties()) {
                    String v = e.getValue().isNull() ? "" : e.getValue().isValueNode() ? e.getValue().asText().trim() : null;
                    if (v == null || !VALUE.matcher(v).matches()) {
                        throw new IllegalArgumentException(file + ": " + service.getKey() + " " + e.getKey() + ": \""
                                + e.getValue() + "\" must be service:<repo-name>, external:<https://host> or empty");
                    }
                    entries.put(e.getKey(), v);
                }
            }
        }
        return new Resolutions(values);
    }

    /** The user's value for a key of a service, null when absent or blank. */
    public String value(String service, String key) {
        return values.getOrDefault(service, Map.of()).values().stream().map(s -> s.get(key))
                .filter(v -> v != null && !v.isEmpty()).findFirst().orElse(null);
    }

    /**
     * Writes {@code found} merged with the values read: existing values are kept, new keys are blank, keys no longer
     * found stay with their value and a {@code # stale} mark.
     */
    public Stats write(Path file, Collection<Found> found) throws IOException {
        Map<String, Map<String, Map<String, Found>>> now = new TreeMap<>();
        found.forEach(f -> now.computeIfAbsent(f.service(), k -> new TreeMap<>())
                .computeIfAbsent(f.section(), k -> new TreeMap<>()).put(f.key(), f));
        Set<String> services = new java.util.TreeSet<>(now.keySet());
        services.addAll(values.keySet());
        int entries = 0;
        int blank = 0;
        int added = 0;
        int stale = 0;
        StringBuilder out = new StringBuilder("""
                # endpoint-map resolutions: name every target the code doesn't reveal. Values:
                #   service:<repo-name>      an internal call, matched to that service's endpoints by verb + path
                #   external:<https://host>  a call leaving the system
                #   ""                       unknown: a red unresolved node (a host: entry keeps what code found)
                # discover rewrites the comments and keeps every value; entries no longer found are marked # stale.
                """);
        for (String service : services) {
            out.append(quote(service)).append(":\n");
            for (String section : SECTIONS) {
                Map<String, Found> current = now.getOrDefault(service, Map.of()).getOrDefault(section, Map.of());
                Map<String, String> old = values.getOrDefault(service, Map.of()).getOrDefault(section, Map.of());
                Set<String> keys = new java.util.TreeSet<>(current.keySet());
                keys.addAll(old.keySet());
                out.append("  ").append(section).append(keys.isEmpty() ? ": {}\n" : ":\n");
                for (String key : keys) {
                    Found f = current.get(key);
                    String value = old.getOrDefault(key, "");
                    entries++;
                    blank += value.isEmpty() ? 1 : 0;
                    added += old.containsKey(key) ? 0 : 1;
                    if (f == null) {
                        stale++;
                    } else {
                        f.usages().forEach(u -> out.append("    # used by ").append(u).append('\n'));
                        if (f.hint() != null) {
                            out.append("    # hint: ").append(f.hint()).append('\n');
                        }
                    }
                    out.append("    ").append(quote(key)).append(": ").append(value.isEmpty() ? "\"\"" : quote(value))
                            .append(f == null ? "  # stale: no longer found in code\n" : "\n");
                }
            }
        }
        Files.writeString(file, out);
        return new Stats(entries, blank, added, stale);
    }

    private static String quote(String s) {
        if (PLAIN.matcher(s).matches()) {
            return s;
        }
        try {
            return new ObjectMapper().writeValueAsString(s); // a JSON string is a valid double-quoted YAML scalar
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
