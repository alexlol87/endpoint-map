package com.acme.customer.session;

/** Key prefixes as constants and a static key builder. */
public final class SessionKeys {

    public static final String PREFIX = "session:";
    public static final String ACTIVE = PREFIX + "active";
    public static final String TOKENS_CACHE = "session-tokens";

    private SessionKeys() {
    }

    public static String session(long customerId) {
        return PREFIX + customerId;
    }
}
