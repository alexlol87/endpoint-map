package com.acme.customer;

import com.acme.customer.session.SessionHandler;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Context path /customers-api + class prefix /customers. */
@RestController
@RequestMapping(path = "/customers")
public class CustomerController {

    private final CustomerService service;
    private final SessionHandler sessions;

    public CustomerController(CustomerService service, SessionHandler sessions) {
        this.service = service;
        this.sessions = sessions;
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

    @GetMapping("/{id}/session")
    public Map<String, String> session(@PathVariable long id) {
        return sessions.load(id);
    }

    @DeleteMapping("/{id}/session")
    public void logout(@PathVariable long id, @RequestHeader("X-Session-Token") String token) {
        sessions.end(id, token);
    }
}
