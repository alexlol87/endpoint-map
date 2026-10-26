package com.endpointmap.endpoints;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * One inbound endpoint. {@code id} is the stable "<service> <METHOD> <path>"; {@code decl} is the handler traced: a method,
 * or a route lambda. {@code controller}: the annotated controller it was found on (an inherited handler's subclass), else null.
 */
public record Endpoint(String service, String method, String path, String handler, String file, int line, String source,
                       Node decl, TypeDeclaration<?> controller) {

    public Endpoint(String service, String method, String path, String handler, String file, int line, String source, Node decl) {
        this(service, method, path, handler, file, line, source, decl, null);
    }

    public String id() {
        return service + " " + method + " " + path;
    }
}
