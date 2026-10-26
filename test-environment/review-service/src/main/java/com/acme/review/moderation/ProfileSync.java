package com.acme.review.moderation;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class ProfileSync {

    /** Two WebClient beans exist: this one is picked by its name, profileWebClient. */
    private final WebClient profileWebClient;

    public ProfileSync(WebClient profileWebClient) {
        this.profileWebClient = profileWebClient;
    }

    /** -> user-profile-service PATCH /profile-api/profiles/{userId} via WebClientCalls.send. */
    public Mono<Void> flagUser(String userId) {
        return WebClientCalls.send(profileWebClient, HttpMethod.PATCH, ModerationPaths.profile(userId));
    }
}
