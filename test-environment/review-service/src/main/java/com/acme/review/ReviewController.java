package com.acme.review;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping("/reviews/product/{sku}")
    public Flux<Map<String, Object>> forProduct(@PathVariable String sku) {
        return service.forProduct(sku);
    }

    @PostMapping("/reviews")
    public Mono<Review> create(@RequestBody Review review) {
        return service.create(review);
    }

    @DeleteMapping("/reviews/{id}")
    public Mono<Void> delete(@PathVariable long id) {
        return service.delete(id);
    }
}
