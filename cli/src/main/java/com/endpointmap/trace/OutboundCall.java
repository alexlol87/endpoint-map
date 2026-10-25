package com.endpointmap.trace;

/**
 * One outbound HTTP call as a detector sees it. {@code baseUrl} is the resolved base ("http://host:port/prefix"),
 * null when it is unknown; {@code unresolvedKey} then names what is missing ("feign:pricing-service",
 * "${SHIPPING_SERVICE_URL}"). {@code path} is the path template below the base.
 */
public record OutboundCall(String client, String verb, String baseUrl, String path, String unresolvedKey) {
}
