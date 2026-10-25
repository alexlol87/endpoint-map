package com.acme.review;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "reviews")
public record Review(@Id Long id, String sku, int rating, String text) {
}
