package com.acme.pricing;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** No class-level @RequestMapping: full paths on each method. */
@RestController
public class PriceController {

    private final PriceService service;

    public PriceController(PriceService service) {
        this.service = service;
    }

    @GetMapping(value = "/prices/{sku}", produces = "application/json")
    public Map<String, Object> price(@PathVariable String sku, @RequestParam(defaultValue = "EUR") String currency) {
        return Map.of("sku", sku, "amount", service.price(sku, currency));
    }

    @PostMapping("/prices/quote")
    public Map<String, Object> quote(@RequestBody Map<String, Object> body) {
        return Map.of("total", service.quote(body));
    }
}
