package com.acme.notification;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Name given as the annotation's value (no url) -> resolutions entry feign:user-profile-service. */
@FeignClient("user-profile-service")
public interface UserProfileClient {

    @GetMapping("/profile-api/profiles/{userId}")
    Map<String, Object> getProfile(@PathVariable("userId") String userId);
}
