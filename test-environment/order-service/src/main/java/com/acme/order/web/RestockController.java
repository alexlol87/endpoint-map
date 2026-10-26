package com.acme.order.web;

import com.acme.order.service.RestockService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders/restock")
public class RestockController {

    private final RestockService restock;

    public RestockController(RestockService restock) {
        this.restock = restock;
    }

    @PostMapping("/{sku}")
    public Map<String, Object> restock(@PathVariable String sku) {
        return restock.restock(sku);
    }
}
