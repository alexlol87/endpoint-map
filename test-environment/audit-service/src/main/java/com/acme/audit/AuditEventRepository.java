package com.acme.audit;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AuditEventRepository extends ReactiveCrudRepository<AuditEvent, Long> {

    @Modifying
    @Query("DELETE FROM audit_event WHERE user_id = :userId")
    Mono<Integer> purgeForUser(String userId);
}
