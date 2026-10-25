package com.acme.audit;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final WebClient vendor;

    public AuditService(AuditEventRepository repository, @Value("${vendor.base-url}") String vendorUrl) {
        this.repository = repository;
        this.vendor = WebClient.builder().baseUrl(vendorUrl).build();
    }

    public Mono<AuditEvent> record(Map<String, Object> event) {
        String type = String.valueOf(event.get("type"));
        Mono<AuditEvent> saved = repository.save(new AuditEvent(null, type, String.valueOf(event.get("userId"))));
        if (type.startsWith("SECURITY")) {
            // external SIEM vendor, described by openapi/openapi-siem-vendor.json
            return saved.flatMap(e -> vendor.post().uri("/v2/events").bodyValue(e).retrieve().toBodilessEntity().thenReturn(e));
        }
        return saved;
    }

    public Mono<AuditEvent> get(long id) {
        return repository.findById(id)
                .switchIfEmpty(vendor.get().uri("/v2/events/{id}", id).retrieve().bodyToMono(AuditEvent.class));
    }
}
