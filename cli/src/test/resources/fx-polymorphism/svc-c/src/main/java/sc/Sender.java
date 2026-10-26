package sc; import reactor.core.publisher.Mono; public interface Sender { Mono<String> send(); }
