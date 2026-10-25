package com.acme.loyalty;

import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LoyaltyService {

    private final PointsRepository points;
    private final CartExchange cart;

    public LoyaltyService(PointsRepository points, CartExchange cart) {
        this.points = points;
        this.cart = cart;
    }

    public Mono<Map<String, Object>> balance(String userId) {
        return points.sumByUserId(userId).map(p -> Map.<String, Object>of("points", p)).defaultIfEmpty(Map.of("points", 0));
    }

    public Mono<Map<String, Object>> tier(String userId) {
        return points.sumByUserId(userId)
                .map(p -> Map.<String, Object>of("tier", p > 1000 ? "GOLD" : p > 100 ? "SILVER" : "BRONZE"));
    }

    /** loyalty -> cart GET /carts/{userId}/points-preview -> loyalty POST /simulate: cross-service cycle. */
    public Mono<Map<String, Object>> simulate(String userId) {
        return cart.pointsPreview(userId)
                .flatMap(preview -> points.save(new PointsEntry(null, userId, ((Number) preview.getOrDefault("points", 0)).intValue()))
                        .thenReturn(preview));
    }
}
