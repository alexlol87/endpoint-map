package com.acme.review.moderation;

import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ModerationService {

    private final ProfileSync profileSync;
    private final StockSync stockSync;
    private final VendorSync vendorSync;

    public ModerationService(ProfileSync profileSync, StockSync stockSync, VendorSync vendorSync) {
        this.profileSync = profileSync;
        this.stockSync = stockSync;
        this.vendorSync = vendorSync;
    }

    public Mono<Void> moderate(long id, String userId, String sku, Map<String, Object> body) {
        return profileSync.flagUser(userId)
                .then(stockSync.release(sku))
                .then(Mono.fromRunnable(() -> vendorSync.report(id, body)))
                .then();
    }
}
