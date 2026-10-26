package com.acme.review.moderation;

/** Static URL helpers: the URL only resolves after following these calls. */
public final class ModerationPaths {

    public static final String PROFILES = "/profiles";

    private ModerationPaths() {
    }

    public static String join(String base, String path) {
        return base.endsWith("/") ? base + path.substring(1) : base + path;
    }

    public static String profile(String userId) {
        return join(PROFILES, "/" + userId);
    }
}
