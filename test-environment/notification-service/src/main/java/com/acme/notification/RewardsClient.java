package com.acme.notification;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Partner API: host from an environment variable, followed by the partner's context path. discover asks about
 * ${REWARDS_HOST} only; /rewards-api stays in front of the path whatever resolutions.yml names as the host.
 */
@FeignClient(name = "rewards", url = "${REWARDS_HOST}/rewards-api")
public interface RewardsClient {

    @GetMapping("/rewards/{userId}")
    Map<String, Object> getRewards(@PathVariable("userId") String userId);
}
