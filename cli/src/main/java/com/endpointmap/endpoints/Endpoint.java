package com.endpointmap.endpoints;

/** One inbound endpoint. {@code id} is the stable "<service> <METHOD> <path>". */
public record Endpoint(String service, String method, String path, String handler, String file, int line, String source) {

    public String id() {
        return service + " " + method + " " + path;
    }
}
