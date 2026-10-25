package com.acme.catalog;

import com.acme.catalog.details.ProductDetailsHandler;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Annotated WebFlux controller. */
@RestController
@RequestMapping("/catalog/products")
public class ProductController {

    private final ProductService service;
    private final ProductDetailsHandler detailsHandler;

    public ProductController(ProductService service, ProductDetailsHandler detailsHandler) {
        this.service = service;
        this.detailsHandler = detailsHandler;
    }

    @GetMapping
    public Flux<Product> list(@RequestParam(required = false) String category) {
        return service.list(category);
    }

    @GetMapping("/{sku}")
    public Mono<Map<String, Object>> get(@PathVariable String sku) {
        return service.getWithPrice(sku);
    }

    /** Deep chain: handler -> details service -> availability/price services -> static util doing the WebClient call. */
    @GetMapping("/{sku}/details")
    public Mono<Map<String, Object>> details(@PathVariable String sku) {
        return detailsHandler.details(sku);
    }

    @PostMapping(path = {"/batch", "/bulk"})
    public Flux<Product> batch(@RequestBody Flux<String> skus) {
        return service.batch(skus);
    }
}
