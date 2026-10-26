package com.endpointmap.resolutions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final String HEADER = """
            # endpoint-map resolutions: name every target the code doesn't reveal. Values:
            #   service:<repo-name>      an internal call, matched to that service's endpoints by verb + path
            #   external:<https://host>  a call leaving the system
            #   ""                       unknown: a red unresolved node (a host: entry keeps what code found)
            # discover rewrites the comments and keeps every value; entries no longer found are marked # stale.
            # Your own comment lines are kept with the key below them, and a comment after a value stays with it.
            """;
    private static final Set<String> HEADER_LINES = Set.copyOf(HEADER.lines().toList());
    private static final String STALE = "# stale: no longer found in code";
    /** "# used by Class.method (path/File.java:12)", as {@link #write} makes it. */
    private static final Pattern USED_BY = Pattern.compile("# used by \\S+ \\(.+:\\d+\\)");
    /** Path of comments after the last key. */
    private static final String END = "\u0001end";
    private static final Pattern VALUE = Pattern.compile("|service:\\S+|external:https?://[^/\\s]+\\S*");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)*");

    /**
     * A key found in code this run: where it is used ({@code usages}, sorted) and what code showed ({@code hints}: one
     * per distinct URL expression, as several clients can share one placeholder).
     */
    public record Found(String service, String section, String key, Set<String> hints, Set<String> usages) {

        /** The hints in one line, the URL expressions of those with the same reason listed together; null if none. */
        public String hint() {
            Map<String, List<String>> byReason = new java.util.LinkedHashMap<>();
            for (String h : hints) {
                int at = h.indexOf("; ");
                byReason.computeIfAbsent(at < 0 ? "" : h.substring(at), k -> new ArrayList<>()).add(at < 0 ? h : h.substring(0, at));
            }
            List<String> out = new ArrayList<>();
            byReason.forEach((reason, codes) -> out.add(String.join(", ", codes) + reason));
            return out.isEmpty() ? null : String.join(" | ", out);
        }
    }

    /** {@code blank} counts unknowns; a blank {@code host:} entry keeps what code found and is counted in {@code hostBlank}. */
    public record Stats(int entries, int blank, int added, int stale, int hostBlank) {
    }

    /** service -> section -> key -> value ("" when blank). */
    private final Map<String, Map<String, Map<String, String>>> values;
    /** The user's comment lines above, and the comment after, a line; by path "service\0section\0key" ("" = file top). */
    private final Map<String, List<String>> notes = new HashMap<>();
    private final Map<String, String> trailing = new HashMap<>();

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
        JsonNode root;
        try {
            root = YAML.readTree(file.toFile());
        } catch (JsonProcessingException e) {
            if (String.valueOf(e.getOriginalMessage()).startsWith("Duplicate field")) {
                throw new IllegalArgumentException(file + ": line " + e.getLocation().getLineNr() + ": duplicate key "
                        + e.getOriginalMessage().substring("Duplicate field ".length())
                        + "; merge the two entries (the file was not changed)");
            }
            throw e;
        }
        Resolutions r = new Resolutions(values);
        r.readComments(Files.readString(file));
        if (root == null || root.isMissingNode() || root.isNull()) {
            return r;
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
                                + e.getValue().asText() + "\" must be service:<repo-name>, external:<https://host> or empty");
                    }
                    entries.put(e.getKey(), v);
                }
            }
        }
        return r;
    }

    /**
     * Keeps the comments the user wrote: a comment line not generated by {@link #write} belongs to the next key (or
     * the file end), a comment after a key's value to that key.
     */
    private void readComments(String text) {
        List<String> pending = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean afterUsedBy = false;
        for (String line : text.replace("\uFEFF", "").split("\\R")) {
            String t = line.strip();
            if (t.startsWith("#")) {
                // only lines in the exact form write() makes are regenerated; "# hint: <x>" only right after its
                // "# used by" lines (every found key has at least one), so a user's own "# hint: ..." note is kept
                boolean generated = HEADER_LINES.contains(t) || USED_BY.matcher(t).matches()
                        || t.startsWith("# hint: ") && afterUsedBy || t.equals(STALE);
                if (!generated) {
                    pending.add(t);
                }
                afterUsedBy = USED_BY.matcher(t).matches();
                continue;
            }
            afterUsedBy = false;
            int comment = commentStart(line);
            String name = keyOf(comment < 0 ? line : line.substring(0, comment));
            if (name == null) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.getLast() >= indent) {
                indents.removeLast();
                names.removeLast();
            }
            indents.add(indent);
            names.add(name);
            String path = String.join("\0", names);
            if (!pending.isEmpty()) {
                notes.put(path, pending);
                pending = new ArrayList<>();
            }
            String after = comment < 0 ? "" : line.substring(comment).replace(STALE, "").strip();
            if (!after.isEmpty() && !after.equals("#")) {
                trailing.put(path, after);
            }
        }
        if (!pending.isEmpty()) {
            notes.put(END, pending);
        }
    }

    /** The key of a "key: value" line, null for anything else. */
    private static String keyOf(String line) {
        try {
            JsonNode n = YAML.readTree(line.strip());
            return n != null && n.isObject() && n.size() == 1 ? n.fieldNames().next() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Index of the {@code #} that starts a comment on a line (outside quotes, after a blank), -1 if none. */
    private static int commentStart(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean afterBlank = i == 0 || Character.isWhitespace(line.charAt(i - 1));
            if (quote == 0 && (c == '"' || c == '\'') && (afterBlank || line.charAt(i - 1) == ':')) {
                quote = c;
            } else if (quote == '"' && c == '\\') {
                i++;
            } else if (quote != 0 && c == quote) {
                quote = 0;
            } else if (quote == 0 && c == '#' && afterBlank) {
                return i;
            }
        }
        return -1;
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
        int hostBlank = 0;
        StringBuilder out = new StringBuilder(HEADER);
        note(out, "", "");
        for (String service : services) {
            note(out, "", service);
            out.append(quote(service)).append(':').append(after(service)).append('\n');
            for (String section : SECTIONS) {
                Map<String, Found> current = now.getOrDefault(service, Map.of()).getOrDefault(section, Map.of());
                Map<String, String> old = values.getOrDefault(service, Map.of()).getOrDefault(section, Map.of());
                Set<String> keys = new java.util.TreeSet<>(current.keySet());
                keys.addAll(old.keySet());
                String sectionPath = service + "\0" + section;
                note(out, "  ", sectionPath);
                out.append("  ").append(section).append(keys.isEmpty() ? ": {}" : ":").append(after(sectionPath)).append('\n');
                for (String key : keys) {
                    Found f = current.get(key);
                    String value = old.getOrDefault(key, "");
                    String path = sectionPath + "\0" + key;
                    entries++;
                    boolean host = key.startsWith("host:");
                    blank += value.isEmpty() && !host ? 1 : 0;
                    hostBlank += value.isEmpty() && host ? 1 : 0;
                    note(out, "    ", path);
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
                            .append(after(path)).append(f == null ? "  " + STALE + "\n" : "\n");
                }
            }
        }
        note(out, "", END);
        // temp file + atomic move: a killed run never leaves the user's file half written
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".resolutions", ".tmp");
        try {
            Files.writeString(tmp, out);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return new Stats(entries, blank, added, stale, hostBlank);
    }

    private void note(StringBuilder out, String indent, String path) {
        notes.getOrDefault(path, List.of()).forEach(l -> out.append(indent).append(l).append('\n'));
    }

    private String after(String path) {
        return trailing.containsKey(path) ? "  " + trailing.get(path) : "";
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
