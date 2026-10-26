package com.acme.review.moderation;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class VendorSync {

    private final RestClient restClient = RestClient.create();

    @Value("${clients.customer-url}")
    private String customerUrl;

    /** Not set in application.yml -> a properties entry in resolutions.yml. */
    @Value("${clients.moderation-vendor-url}")
    private String vendorUrl;

    public void report(long id, Map<String, Object> body) {
        // String.format + .patch() -> customer-service PATCH /customers-api/customers/{id}/preferences
        restClient.patch().uri(String.format("%s/customers/%d/preferences", customerUrl, id)).body(body).retrieve().toBodilessEntity();
        // unknown property -> properties entry clients.moderation-vendor-url
        restClient.post().uri(vendorUrl + "/v1/reports").body(body).retrieve().toBodilessEntity();
        // URL taken from the request body -> runtime-built, a callSites entry
        restClient.post().uri(String.valueOf(body.get("callbackUrl"))).body(Map.of("id", id)).retrieve().toBodilessEntity();
    }
}
