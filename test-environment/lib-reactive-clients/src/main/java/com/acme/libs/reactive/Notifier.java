package com.acme.libs.reactive;

import reactor.core.publisher.Mono;

/** Interface with several implementations (library + services) -> "oneOf" alternatives. */
public interface Notifier {
    Mono<Void> notify(String userId, String message);
}
