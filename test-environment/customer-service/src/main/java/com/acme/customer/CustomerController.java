package com.acme.customer;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Context path /customers-api + class prefix /customers. */
@RestController
@RequestMapping(path = "/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public CustomerEntity get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public CustomerEntity update(@PathVariable long id, @RequestBody CustomerEntity body) {
        return service.update(id, body);
    }

    /** @RequestMapping with an array of verbs -> two endpoints. */
    @RequestMapping(value = "/{id}/preferences", method = {RequestMethod.GET, RequestMethod.PATCH})
    public Map<String, String> preferences(@PathVariable long id) {
        return service.preferences(id);
    }

    @GetMapping("/{id}/orders-summary")
    public ResponseEntity<Map<String, Object>> ordersSummary(@PathVariable long id) {
        return ResponseEntity.ok(service.ordersSummary(id));
    }
}
