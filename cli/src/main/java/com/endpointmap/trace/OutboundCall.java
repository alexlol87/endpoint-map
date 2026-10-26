package com.endpointmap.trace;

/**
 * One outbound HTTP call as a detector sees it. {@code baseUrl} is the resolved base ("http://host:port/prefix"),
 * null when it is unknown; {@code unresolvedKey} then names what is missing ("feign:pricing-service",
 * "${SHIPPING_SERVICE_URL}"). {@code path} is the path template below the base. {@code source} is the URL expression
 * as written in code (a hint for the user), null when the code gives none. {@code label} names the client method in
 * {@code via} ("WebClient.get"); null means the workspace method called (a Feign interface method).
 * A database call instead has {@code client} jpa/r2dbc, {@code table}, {@code op} read/write/delete and the repository
 * method as {@code label}; its HTTP fields are null. {@code low}: the detector itself guessed (one of several
 * entities a type variable may stand for): the edge is low confidence.
 */
public record OutboundCall(String client, String verb, String baseUrl, String path, String unresolvedKey,
                           String source, String label, String table, String op, boolean low) {

    public OutboundCall(String client, String verb, String baseUrl, String path, String unresolvedKey, String source, String label) {
        this(client, verb, baseUrl, path, unresolvedKey, source, label, null, null, false);
    }

    public static OutboundCall db(String tech, String table, String op, String repoMethod, boolean low) {
        return new OutboundCall(tech, null, null, null, null, null, repoMethod, table, op, low);
    }
}
