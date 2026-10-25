package com.acme.tax;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tax")
public class TaxController {

    private final TaxService service;

    public TaxController(TaxService service) {
        this.service = service;
    }

    @GetMapping("/rates")
    public Map<Object, Object> all() {
        return service.allRates();
    }

    @GetMapping("/rates/{region}")
    public Map<String, Object> rate(@PathVariable String region) {
        return Map.of("region", region, "rate", service.rate(region));
    }

    @PostMapping("/calculate")
    public Map<String, Object> calculate(@RequestBody Map<String, Object> body) {
        return Map.of("tax", service.calculate(body));
    }
}
