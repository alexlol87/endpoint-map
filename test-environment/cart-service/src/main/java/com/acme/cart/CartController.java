package com.acme.cart;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/carts")
public class CartController {

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping("/{userId}")
    public Mono<Map<String, Object>> get(@PathVariable String userId) {
        return service.view(userId);
    }

    @PostMapping("/{userId}/items")
    public Mono<Boolean> add(@PathVariable String userId, @RequestBody Map<String, String> item) {
        return service.add(userId, item.get("sku"));
    }

    @DeleteMapping("/{userId}")
    public Mono<Boolean> clear(@PathVariable String userId) {
        return service.clear(userId);
    }

    /** Called by loyalty-service, and it calls loyalty back -> cross-service cycle. */
    @GetMapping("/{userId}/points-preview")
    public Mono<Map<String, Object>> pointsPreview(@PathVariable String userId) {
        return service.pointsPreview(userId);
    }
}
