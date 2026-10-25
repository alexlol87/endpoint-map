package com.acme.loyalty;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface PointsRepository extends ReactiveCrudRepository<PointsEntry, Long> {

    @Query("SELECT COALESCE(SUM(points), 0) FROM loyalty_points WHERE user_id = :userId")
    Mono<Integer> sumByUserId(String userId);
}
