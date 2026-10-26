package com.acme.billing;

import com.acme.libs.common.DeepChainCaller;
import com.acme.libs.common.InternalRestTemplateClient;
import com.acme.libs.common.ServiceEndpointsProperties;
import com.acme.libs.common.UrlTemplates;
import com.acme.libs.http.ApiGateway;
import com.acme.libs.http.HttpPaths;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/** Every outbound call goes through a library wrapper whose URL is a parameter. */
@Service
public class BillingService {

    private static final String TAX_CALC = "/tax/calculate";

    private final ApiGateway gateway = new ApiGateway();
    private final InternalRestTemplateClient restClient = new InternalRestTemplateClient(new RestTemplate());
    private final DeepChainCaller deepChain = new DeepChainCaller(restClient);
    private final ServiceEndpointsProperties endpoints;

    @Value("${clients.tax-url}")
    private String taxUrl;

    public BillingService(ServiceEndpointsProperties endpoints) {
        this.endpoints = endpoints;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createInvoice(Map<String, Object> request) {
        // wrapper with base URL + path constant arguments (1 level)
        Map<String, Object> tax = gateway.post(taxUrl, TAX_CALC, request, Map.class);
        // @ConfigurationProperties field with a default value
        restClient.send(endpoints.getNotificationUrl() + "/notifications/" + request.get("customerId"), Map.of("text", "invoice"), Void.class);
        if ("shipping".equals(request.get("type"))) {
            // verb passed as argument
            gateway.call(HttpMethod.GET, endpoints.getShippingUrl() + HttpPaths.SHIPMENTS + "/" + request.get("orderId"), Map.class);
        }
        return tax;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getInvoice(long id) {
        // UriComponentsBuilder in the library
        return restClient.fetch(UrlTemplates.invoiceUrl(endpoints.getBillingUrl(), id) + "/history", Map.class);
    }

    public void syncTenant(String tenant, String callbackUrl) {
        // String.format in the library
        String url = UrlTemplates.tenantUrl(endpoints.getShippingUrl(), tenant, "/shipments");
        List<?> shipments = restClient.fetch(url, List.class);
        for (Object s : shipments) {
            restClient.replace(UrlTemplates.join(taxUrl, "tax", "rates", String.valueOf(s)), s);
        }
        // URL arrives from the request body -> runtime-built, needs a callSites entry
        restClient.send(callbackUrl, Map.of("tenant", tenant), Void.class);
        // 4 levels of parameter passing -> beyond constant propagation depth
        deepChain.level1(endpoints.getBillingUrl() + "/billing/invoices/0");
        // the same URL through 3 calls resolves (GET /shipments/{id}); through 4 it is a callSites entry
        deepChain.threeLevels(endpoints.getShippingUrl() + "/shipments/" + tenant);
        deepChain.fourLevels(endpoints.getShippingUrl() + "/shipments/" + tenant);
    }
}
