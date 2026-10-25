package com.endpointmap.trace;

import com.endpointmap.workspace.Workspace.Repo;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import java.util.Map;

/**
 * A call met while walking a method body: a {@code MethodCallExpr} or {@code MethodReferenceExpr}, the workspace
 * methods it may invoke (empty for framework/JDK calls), and the service the trace started in, whose config
 * resolves placeholders even inside library code. {@code receiverTypes} is the static type of the call's receiver
 * followed by its workspace supertypes (empty when unknown): a {@code @FeignClient} interface may inherit all its
 * methods from a shared API interface.
 */
public record CallSite(Expression call, List<MethodDeclaration> callees, List<TypeDeclaration<?>> receiverTypes,
                       Repo service, Map<String, String> config) {
}
