package com.endpointmap.endpoints;

/** URL path normalisation shared by every endpoint source. */
public final class UrlPaths {

    private UrlPaths() {
    }

    /** Joins pieces with single slashes, no trailing slash, and strips regex from template vars ({code:[A-Z]{3}} -> {code}). */
    public static String join(String... pieces) {
        StringBuilder sb = new StringBuilder();
        for (String p : pieces) {
            if (p != null && !p.isBlank()) {
                sb.append('/').append(p.trim());
            }
        }
        String path = stripRegex(sb.toString()).replaceAll("/{2,}", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    private static String stripRegex(String path) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean skipping = false;
        for (char ch : path.toCharArray()) {
            if (ch == '{') {
                depth++;
                if (depth > 1 && skipping) {
                    continue;
                }
            } else if (ch == '}') {
                depth--;
                if (depth > 0 && skipping) {
                    continue;
                }
                skipping = false;
            } else if (ch == ':' && depth == 1) {
                skipping = true;
                continue;
            }
            if (!skipping) {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
