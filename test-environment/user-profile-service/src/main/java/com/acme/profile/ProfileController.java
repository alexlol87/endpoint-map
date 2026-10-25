package com.acme.profile;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** spring.webflux.base-path=/profile-api is prepended to every path. */
@RestController
@RequestMapping("/profiles")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping("/{userId}")
    public Mono<Map<String, Object>> get(@PathVariable String userId) {
        return service.get(userId);
    }

    @PatchMapping("/{userId}")
    public Mono<UserProfile> update(@PathVariable String userId, @RequestBody Map<String, Object> changes) {
        return service.update(userId, changes);
    }
}
