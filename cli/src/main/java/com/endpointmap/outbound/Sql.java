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
            "update", "write", "delete", "delete");
    private static final Pattern NOISE = Pattern.compile("'(?:[^']|'')*'|--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern TABLE = Pattern.compile(
            "(?i)\\b(FROM|JOIN|INTO|UPDATE)\\s+(?:(?:ONLY|LATERAL|FETCH)\\s+)?([\\w.$\"`\\[\\]{}*]+)(\\s*\\()?");
    private static final Pattern CTE = Pattern.compile("(?i)\\b(\\w+)\\s*(?:\\([^()]*\\))?\\s+AS\\s*\\(");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_]+");
    /** Words that may follow UPDATE/INTO/FROM without naming a table: ON CONFLICT DO UPDATE SET, FOR UPDATE OF. */
    private static final Set<String> NOT_NAMES = Set.of("set", "of", "nowait", "skip", "select", "values");

    private Sql() {
    }

    // shortcut: only the first name after FROM is seen (FROM a, b misses b), nor MERGE ... USING src; add a list
    // reader when real code needs it
    /** Null when the statement is not understood (no leading verb); empty when it names no table. */
    static List<Access> parse(String sql, boolean jpql) {
        String s = NOISE.matcher(sql).replaceAll(m -> m.group().startsWith("'") ? "''" : " ");
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
        List<Access> out = new ArrayList<>();
        Matcher m = TABLE.matcher(s);
        boolean targetSeen = op.equals("read");
        String target = null;
        while (m.find()) {
            String kw = m.group(1).toLowerCase(Locale.ROOT);
            String name = m.group(2).replaceAll("[\"`\\[\\]]", "");
            boolean function = m.group(3) != null && (kw.equals("from") || kw.equals("join"));
            if (inCall[m.start()] || function || NOT_NAMES.contains(name.toLowerCase(Locale.ROOT))
                    || ctes.contains(name.toLowerCase(Locale.ROOT)) || jpql && kw.equals("join") && name.contains(".")) {
                continue; // a JPQL path join (join o.lines l) names an association, not an entity
            }
            if (jpql && name.contains(".")) {
                name = name.substring(name.lastIndexOf('.') + 1); // a fully qualified entity class
            }
            String table = name.matches(".*[{}$*].*") ? null : name;
            if (!targetSeen && m.start() >= verbAt) {
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
}
