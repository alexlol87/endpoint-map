package com.endpointmap.trace;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.Map;

/** Evaluates a call's arguments in the caller's context, so the callee is walked with the values it was given. */
public interface ArgumentBinder {

    int MAX_HOPS = 3;

    /** Parameter name -> value, only for parameters whose value is known; empty when none is. */
    Map<String, ArgValue> bind(MethodDeclaration callee, MethodCallExpr call, CallSite caller);

    ArgumentBinder NONE = (callee, call, caller) -> Map.of();
}
