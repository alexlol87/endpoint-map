package com.acme.analytics;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public Mono<Void> event(@RequestBody Map<String, Object> event) {
        return service.track(event);
    }

    @GetMapping("/trending")
    public Flux<Map<String, Object>> trending(@RequestParam(required = false) String sku) {
        return service.trending(sku);
    }

    @GetMapping("/users/{userId}/searches")
    public Mono<List<String>> searches(@PathVariable String userId) {
        return service.searches(userId);
    }

    @GetMapping("/export")
    public Flux<Map<String, Object>> export() {
        return service.exportAll(0);
    }
}
