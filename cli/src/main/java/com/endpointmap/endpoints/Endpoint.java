package com.endpointmap.endpoints;

import com.github.javaparser.ast.body.MethodDeclaration;

/** One inbound endpoint. {@code id} is the stable "<service> <METHOD> <path>"; {@code decl} is the handler method traced. */
public record Endpoint(String service, String method, String path, String handler, String file, int line, String source,
                       MethodDeclaration decl) {

    public String id() {
        return service + " " + method + " " + path;
    }
}
