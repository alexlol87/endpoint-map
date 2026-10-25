package com.acme.billing;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE)
public class BillingController {

    private final BillingService service;

    public BillingController(BillingService service) {
        this.service = service;
    }

    @PostMapping("/invoices")
    public Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return service.createInvoice(request);
    }

    @GetMapping("/invoices/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return service.getInvoice(id);
    }

    @PostMapping("/tenants/{tenant}/sync")
    public void sync(@PathVariable String tenant, @RequestBody Map<String, String> body) {
        service.syncTenant(tenant, body.get("callbackUrl"));
    }
}
