package com.endpointmap.endpoints;

import com.github.javaparser.ast.Node;

/** One inbound endpoint. {@code id} is the stable "<service> <METHOD> <path>"; {@code decl} is the handler traced: a method, or a route lambda. */
public record Endpoint(String service, String method, String path, String handler, String file, int line, String source,
                       Node decl) {

    public String id() {
        return service + " " + method + " " + path;
    }
}
