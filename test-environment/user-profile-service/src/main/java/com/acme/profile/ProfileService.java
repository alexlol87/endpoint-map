package com.acme.profile;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class ProfileService {

    private final UserProfileRepository repository;
    private final WebClient loyaltyClient;
    private final WebClient auditClient;

    /** Base URLs must be followed from the @Bean definitions, by qualifier. */
    public ProfileService(UserProfileRepository repository,
                          @Qualifier("loyalty") WebClient loyaltyClient,
                          @Qualifier("audit") WebClient auditClient) {
        this.repository = repository;
        this.loyaltyClient = loyaltyClient;
        this.auditClient = auditClient;
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> get(String userId) {
        return repository.findByUserId(userId)
                .zipWith(loyaltyClient.get().uri("/loyalty/{userId}/tier", userId).retrieve().bodyToMono(Map.class))
                .map(t -> Map.of("profile", t.getT1(), "tier", t.getT2()));
    }

    public Mono<UserProfile> update(String userId, Map<String, Object> changes) {
        return repository.findByUserId(userId)
                .map(p -> p.withEmail(String.valueOf(changes.getOrDefault("email", p.email()))))
                .flatMap(repository::save)
                .flatMap(saved -> auditClient.post().uri("/audit/events")
                        .bodyValue(Map.of("type", "PROFILE_UPDATED", "userId", userId))
                        .retrieve().toBodilessEntity()
                        .thenReturn(saved));
    }
}
