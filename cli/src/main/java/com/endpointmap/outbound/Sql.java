package com.endpointmap.outbound;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tables and operation of one SQL or JPQL statement, read conservatively (PLAN.md: a simple regex over
 * {@code FROM/JOIN/INTO/UPDATE <name>}). The op comes from the leading verb (after a {@code WITH} list, the first
 * verb outside parentheses) and goes to the statement's target table; every other table is read. Literals and
 * comments are ignored, so are {@code FROM} inside a function call ({@code EXTRACT(YEAR FROM d)}), table functions
 * ({@code FROM unnest(..)}) and CTE names. A name not known from code ({@code {*}}, {@code ${x}}) gives a null table.
 */
final class Sql {

    record Access(String table, String op) {
    }

    private static final Map<String, String> VERBS = Map.of("select", "read", "insert", "write", "merge", "write",
            "update", "write", "delete", "delete", "replace", "write");
    private static final Pattern NOISE = Pattern.compile("'(?:[^']|'')*'|--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern TABLE = Pattern.compile(
            "(?i)\\b(FROM|JOIN|INTO|UPDATE)\\s+(?:(?:ONLY|LATERAL|FETCH)\\s+)?([\\w.$\"`\\[\\]{}*]+)(\\s*\\()?");
    private static final Pattern CTE = Pattern.compile("(?i)\\b(\\w+)\\s*(?:\\([^()]*\\))?\\s+AS\\s*(?:(?:NOT\\s+)?MATERIALIZED\\s*)?\\(");
    /** The next item of a comma list after a FROM name: {@code [AS] [alias] , <name>} (FROM a x, b y). */
    private static final Pattern LIST = Pattern.compile("(?i)\\G(?:\\s+(?:AS\\s+)?\\w+)?\\s*,\\s*(?:(?:ONLY|LATERAL)\\s+)?([\\w.$\"`\\[\\]{}*]+)(\\s*\\()?");
    /** MySQL's upsert tail: its UPDATE names columns, not a table. */
    private static final Pattern UPSERT = Pattern.compile("(?i)\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b.*", Pattern.DOTALL);
    private static final Pattern DEFAULT_SCHEMA = Pattern.compile("^(public|dbo)\\.");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_]+");
    /** Words that may follow UPDATE/INTO/FROM without naming a table: ON CONFLICT DO UPDATE SET, FOR UPDATE OF. */
    private static final Set<String> NOT_NAMES = Set.of("set", "of", "nowait", "skip", "select", "values");

    private Sql() {
    }

    // shortcut: MERGE ... USING src and a list item after a subquery (FROM (..) x, b) are not read; add them when real code needs it
    /** Null when the statement is not understood (no leading verb); empty when it names no table. */
    static List<Access> parse(String sql, boolean jpql) {
        if (jpql) {
            sql = sql.replaceFirst("(?i)^\\s*delete\\s+(?!from\\b)", "delete from "); // HQL: delete Order o where ..
        }
        String s = UPSERT.matcher(NOISE.matcher(sql).replaceAll(m -> m.group().startsWith("'") ? "''" : " ")).replaceAll("");
        int[] depth = new int[s.length() + 1];
        boolean[] inCall = new boolean[s.length() + 1]; // inside the parentheses of a function call, not a subquery
        List<Boolean> stack = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                boolean afterName = i > 0 && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == '_');
                stack.add(afterName && !s.substring(i + 1).stripLeading().toLowerCase(Locale.ROOT).startsWith("select"));
            } else if (c == ')' && !stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            }
            depth[i + 1] = stack.size();
            inCall[i + 1] = !stack.isEmpty() && stack.get(stack.size() - 1);
        }
        Matcher w = WORD.matcher(s);
        if (!w.find()) {
            return null;
        }
        Set<String> ctes = new HashSet<>();
        int verbAt = w.start();
        String verb = w.group().toLowerCase(Locale.ROOT);
        if (jpql && verb.equals("from")) {
            verb = "select"; // HQL: from Order o where ..
        }
        if (verb.equals("with")) {
            Matcher cte = CTE.matcher(s);
            while (cte.find()) {
                ctes.add(cte.group(1).toLowerCase(Locale.ROOT));
            }
            verb = null;
            while (w.find()) {
                if (depth[w.start()] == 0 && VERBS.containsKey(w.group().toLowerCase(Locale.ROOT))) {
                    verb = w.group().toLowerCase(Locale.ROOT);
                    verbAt = w.start();
                    break;
                }
            }
        }
        String op = verb == null ? null : VERBS.get(verb);
        if (op == null) {
            return null;
        }
        record Name(String kw, String name, boolean function, int at) {
        }
        List<Name> names = new ArrayList<>();
        Matcher m = TABLE.matcher(s);
        while (m.find()) {
            String kw = m.group(1).toLowerCase(Locale.ROOT);
            boolean function = m.group(3) != null && (kw.equals("from") || kw.equals("join"));
            names.add(new Name(kw, m.group(2), function, m.start()));
            if (kw.equals("from") && !function) { // FROM a x, b y: every item of the list
                Matcher l = LIST.matcher(s).region(m.end(), s.length());
                while (l.find()) {
                    names.add(new Name(kw, l.group(1), l.group(2) != null, m.start()));
                }
            }
        }
        List<Access> out = new ArrayList<>();
        boolean targetSeen = op.equals("read");
        String target = null;
        for (Name n : names) {
            String name = jpql ? n.name().replaceAll("[\"`\\[\\]]", "") : normalize(n.name());
            if (inCall[n.at()] || n.function() || NOT_NAMES.contains(name.toLowerCase(Locale.ROOT))
                    || ctes.contains(name.toLowerCase(Locale.ROOT)) || jpql && n.kw().equals("join") && name.contains(".")) {
                continue; // a JPQL path join (join o.lines l) names an association, not an entity
            }
            if (jpql && name.contains(".")) {
                name = name.substring(name.lastIndexOf('.') + 1); // a fully qualified entity class
            }
            String table = name.matches(".*[{}$*].*") ? null : name;
            if (!targetSeen && n.at() >= verbAt) {
                targetSeen = true;
                target = table;
                out.add(new Access(table, op));
            } else {
                out.add(new Access(table, "read"));
            }
        }
        String written = target;
        return out.stream().filter(a -> !a.op().equals("read") || a.table() == null || !a.table().equals(written)).distinct().toList();
    }

    /**
     * A SQL table name as one node: unquoted parts lower-cased (SQL folds them), quoted ones kept as written, quotes
     * dropped; the default schema ({@code public.}, {@code dbo.}) dropped.
     */
    // shortcut: only public/dbo count as the default schema; read spring.jpa.properties.hibernate.default_schema when needed
    static String normalize(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.split("\\.", -1)) {
            boolean quoted = part.matches("[\"`\\[].*");
            out.append(out.isEmpty() ? "" : ".").append(quoted ? part.replaceAll("[\"`\\[\\]]", "") : part.toLowerCase(Locale.ROOT));
        }
        return DEFAULT_SCHEMA.matcher(out).replaceFirst("");
    }
}
