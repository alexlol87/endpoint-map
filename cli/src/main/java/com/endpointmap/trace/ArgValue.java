package com.endpointmap.trace;

/**
 * What a caller passed for one parameter, as far as code tells: a URL template ("${x}/items/{*}"), a client's base URL
 * ("" when it has none) or a verb. {@code hops} counts the calls it was passed through; past
 * {@link ArgumentBinder#MAX_HOPS} it is no longer propagated (PLAN.md: constant propagation up to 3 call levels).
 */
public record ArgValue(String text, int hops) {
}
