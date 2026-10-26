package com.endpointmap.outbound;

import com.endpointmap.config.ConfigResolver;
import com.endpointmap.endpoints.AnnotationEndpointDetector;
import com.endpointmap.endpoints.AnnotationEndpointDetector.Mapping;
import com.endpointmap.endpoints.ConstantEvaluator;
import com.endpointmap.endpoints.UrlPaths;
import com.endpointmap.trace.CallSite;
import com.endpointmap.trace.OutboundCall;
import com.endpointmap.trace.OutboundDetector;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A call to a method of a {@code @FeignClient(name, url, path)} interface: verb and path from the method's
 * {@code @*Mapping}, base URL from {@code url} (literal, constant or {@code ${prop:default}} resolved with the calling
 * service's config). No {@code url}, or a placeholder nothing defines, leaves the base unresolved.
 */
public final class FeignDetector implements OutboundDetector {

    private final AnnotationEndpointDetector mappings;
    private final ConstantEvaluator constants;

    public FeignDetector(AnnotationEndpointDetector mappings, ConstantEvaluator constants) {
        this.mappings = mappings;
        this.constants = constants;
    }

    @Override
    public List<OutboundCall> detect(CallSite site) {
        List<OutboundCall> out = new ArrayList<>();
        for (MethodDeclaration m : site.callees()) {
            // the receiver's type first: "interface ItemClient extends ItemApi" puts @FeignClient above the declaring type
            Stream<TypeDeclaration<?>> owners = Stream.concat(site.receiverTypes().stream(), m.getParentNode()
                    .filter(TypeDeclaration.class::isInstance).map(t -> (TypeDeclaration<?>) t).stream());
            Optional<AnnotationExpr> feign = owners.flatMap(o -> o.getAnnotations().stream())
                    .filter(a -> a.getName().getIdentifier().equals("FeignClient")).findFirst();
            Optional<Mapping> mapping = mappings.mappingOf(m);
            if (feign.isEmpty() || mapping.isEmpty()) {
                continue; // not Feign, or a default method of the interface: keep walking
            }
            String name = attr(feign.get(), site, "name", "value").orElse("?");
            Optional<String> rawUrl = attrRaw(feign.get(), "url").filter(u -> !u.isBlank());
            String url = rawUrl.map(u -> ConfigResolver.resolve(u, site.config())).orElse(null);
            // a url the evaluator can't read (a constant from a jar) leaves nothing to name: the call site is the key
            String key = rawUrl.isPresent() ? (url.contains("{*}") ? rawUrl.get() : null)
                    : hasAttr(feign.get(), "url") ? null : "feign:" + name;
            String prefix = attr(feign.get(), site, "path").orElse("");
            for (String verb : mapping.get().verbs()) {
                for (String path : mapping.get().paths()) {
                    // Feign sends GET when the mapping names no method
                    out.add(new OutboundCall("feign", verb.equals("ANY") ? "GET" : verb, key == null ? url : null,
                            UrlPaths.join(prefix, path), key));
                }
            }
        }
        return out;
    }

    private Optional<String> attr(AnnotationExpr a, CallSite site, String... names) {
        return attrRaw(a, names).map(v -> ConfigResolver.resolve(v, site.config()));
    }

    private static boolean hasAttr(AnnotationExpr a, String name) {
        return a instanceof NormalAnnotationExpr n && n.getPairs().stream().anyMatch(p -> p.getNameAsString().equals(name));
    }

    private Optional<String> attrRaw(AnnotationExpr a, String... names) {
        for (String name : names) {
            if (a instanceof SingleMemberAnnotationExpr s && name.equals("value")) {
                return constants.eval(s.getMemberValue(), a);
            }
            if (a instanceof NormalAnnotationExpr n) {
                for (MemberValuePair p : n.getPairs()) {
                    if (p.getNameAsString().equals(name)) {
                        return constants.eval(p.getValue(), a);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
