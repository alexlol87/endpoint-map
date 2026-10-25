package com.acme.warehouse;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/warehouse")
public class WarehouseController extends BaseController {

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @GetMapping("/stock/hot")
    public Mono<String> hot() {
        return service.hotStock();
    }

    @PostMapping("/dispatch/{sku}")
    public Mono<Map<String, Object>> dispatch(@PathVariable String sku) {
        return service.dispatch(sku);
    }
}
