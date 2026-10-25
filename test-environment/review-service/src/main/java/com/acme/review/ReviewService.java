package com.acme.review;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Service
public class ReviewService {

    private final DatabaseClient db;
    private final R2dbcEntityTemplate template;
    private final WebClient catalog;

    public ReviewService(DatabaseClient db, R2dbcEntityTemplate template, @Value("${catalog.url}") String catalogUrl) {
        this.db = db;
        this.template = template;
        this.catalog = WebClient.builder().baseUrl(catalogUrl).build();
    }

    public Flux<Map<String, Object>> forProduct(String sku) {
        return db.sql("SELECT r.id, r.rating, r.text FROM reviews r JOIN review_votes v ON v.review_id = r.id WHERE r.sku = :sku")
                .bind("sku", sku)
                .fetch()
                .all()
                .onErrorResume(e -> db.sql("select * from reviews_archive where sku = :sku").bind("sku", sku).fetch().all());
    }

    public Mono<Review> create(Review review) {
        return catalog.get().uri("/catalog/products/{sku}", review.sku()).retrieve().bodyToMono(Map.class)
                .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.error(new IllegalArgumentException("no product")))
                .then(template.insert(review))
                .flatMap(saved -> db.sql("UPDATE product_ratings SET count = count + 1 WHERE sku = :sku")
                        .bind("sku", saved.sku())
                        .fetch().rowsUpdated()
                        .retry(2)
                        .thenReturn(saved));
    }

    public Mono<Void> delete(long id) {
        return template.delete(query(where("id").is(id)), Review.class)
                .then(db.sql("DELETE FROM review_votes WHERE review_id = :id").bind("id", id).then());
    }
}
