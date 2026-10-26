package com.acme.order.util;

import com.acme.libs.http.HttpPaths;
import com.acme.order.data.TaxRateSnapshot;
import com.acme.order.data.TaxRateSnapshotRepository;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * A "utils" class that does I/O: last hop of the checkout chain -> tax-service GET /tax-api/tax/rates/{region}, and a
 * snapshot of the rate through a repository built on a library base repository (db edges 5 hops down).
 */
@Component
public class TaxRestUtils {

    private final RestTemplate restTemplate = new RestTemplate();
    private final TaxRateSnapshotRepository snapshots;

    @Value("${tax.base-url}")
    private String taxBaseUrl;

    public TaxRestUtils(TaxRateSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    public double fetchRate(String region) {
        Map<?, ?> body = restTemplate.getForObject(UrlUtils.join(taxBaseUrl, HttpPaths.TAX_RATE_FOR_REGION), Map.class, region);
        double rate = ((Number) body.get("rate")).doubleValue();
        snapshots.removeByRegion(region);
        snapshots.save(new TaxRateSnapshot(region, rate));
        return rate;
    }
}
