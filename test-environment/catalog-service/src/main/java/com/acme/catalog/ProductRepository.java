package com.acme.catalog;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {

    Mono<Product> findBySku(String sku);

    @Query("SELECT p.* FROM products p JOIN categories c ON c.id = p.category_id WHERE c.name = :category")
    Flux<Product> findByCategory(String category);
}
